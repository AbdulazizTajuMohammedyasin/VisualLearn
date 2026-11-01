package com.visuallearn.app.linux

import android.content.Context
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * A Debian 12 userland (armhf, or arm64 on 64-bit processes) run through proot from
 * the app's private storage.
 *
 * proot is Termux's build (packages.termux.dev, proot 5.1.107.101) with its NEEDED
 * entry patched from libtalloc.so.2 to libtalloc.so so Android extracts it as a
 * native library. An older 2023 build broke dpkg's hard-link lock files.
 *
 * Debian 12 rather than 13: on 32-bit Android userspace with older kernels, Debian 13's
 * 64-bit time ABI and newer packages misbehave; Debian 12 is the last release with a
 * classic armhf ABI and still gets Chromium security updates.
 */
class LinuxEnv(private val context: Context) {

    val baseDir = File(context.filesDir, "linux")
    val rootfs = File(baseDir, "debian")
    private val tmpDir = File(baseDir, "tmp")
    private val fakeProcDir = File(baseDir, "proc")
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private val proot = File(nativeDir, "libproot.so")
    private val loader = File(nativeDir, "libproot-loader.so")

    val isInstalled: Boolean get() = File(rootfs, ".visuallearn-installed").exists()

    /** Maps a path inside the guest (e.g. "/root/out/a.html") to the host file. */
    fun guestFile(path: String): File = File(rootfs, path.removePrefix("/"))

    suspend fun installRootfs(log: (String) -> Unit) = withContext(Dispatchers.IO) {
        baseDir.mkdirs()
        val archive = File(baseDir, "rootfs.tar.xz")
        val spec = rootfsSpec
        if (!archive.exists() || sha256(archive) != spec.sha256) {
            log("Downloading Debian 12 rootfs…")
            download(spec.url, archive, log)
            val sum = sha256(archive)
            check(sum == spec.sha256) { "rootfs checksum mismatch: $sum" }
        }
        log("Extracting…")
        rootfs.deleteRecursively()
        rootfs.mkdirs()
        extractTarXz(archive, rootfs, stripPrefix = spec.topDir, log)
        archive.delete()

        configure()
        log("Patching glibc vDSO lookup…")
        log(if (patchVdso()) "vDSO patch applied" else "vDSO patch not needed")
        File(rootfs, ".visuallearn-installed").writeText("1")
        log("Debian rootfs ready.")
    }

    /**
     * Runs [script] with /bin/sh inside the guest as fake root, streaming merged
     * stdout/stderr lines to [onLine]. Returns the exit code.
     */
    suspend fun run(
        script: String,
        extraEnv: Map<String, String> = emptyMap(),
        onStart: (Process) -> Unit = {},
        onLine: (String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(command("/bin/sh", "-c", script))
            .redirectErrorStream(true)
            .also { pb ->
                pb.environment().clear()
                pb.environment().putAll(environment())
                pb.environment().putAll(extraEnv)
            }
            .start()
        onStart(process)
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
        process.waitFor()
    }

    private fun command(vararg guestArgs: String): List<String> {
        tmpDir.mkdirs()
        val args = mutableListOf(
            proot.absolutePath,
            "-r", rootfs.absolutePath,
            "-0",
            "-w", "/root",
            "--link2symlink",
            "--kill-on-exit",
            "-L",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${File(rootfs, "tmp").absolutePath}:/dev/shm",
        )
        // Android denies apps some /proc files that Node and Chromium read; bind stand-ins.
        for (name in FAKE_PROC.keys) {
            if (!File("/proc/$name").canRead()) {
                args += listOf("-b", "${File(fakeProcDir, name).absolutePath}:/proc/$name")
            }
        }
        args += guestArgs
        return args
    }

    private fun environment(): Map<String, String> = mapOf(
        "PROOT_TMP_DIR" to tmpDir.absolutePath,
        "PROOT_LOADER" to loader.absolutePath,
        // For proot's own libtalloc/libandroid-shmem; the path doesn't exist inside the guest.
        "LD_LIBRARY_PATH" to nativeDir.absolutePath,
        "HOME" to "/root",
        "USER" to "root",
        "LANG" to "C.UTF-8",
        "TERM" to "xterm-256color",
        "TMPDIR" to "/tmp",
        "PATH" to "/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "CHROME_PATH" to "/usr/bin/chromium",
        "DEBIAN_FRONTEND" to "noninteractive",
    )

    private fun configure() {
        File(rootfs, "etc/resolv.conf").apply { delete(); writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n") }
        File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        File(rootfs, "etc/apt/apt.conf.d/01-proot").writeText("APT::Sandbox::User \"root\";\n")
        fakeProcDir.mkdirs()
        FAKE_PROC.forEach { (name, content) -> File(fakeProcDir, name).writeText(content) }
        File(rootfs, "tmp").mkdirs()
    }

    /**
     * Some 32-bit-on-arm64 kernels (seen on Samsung 4.19) export a compat
     * `__vdso_clock_gettime64` that fills in the old 32-bit timespec, so every
     * clock read in glibc returns garbage. Renaming the symbol in ld.so makes
     * glibc fall back to real syscalls, which are correct.
     */
    fun patchVdso(): Boolean {
        val ld = File(rootfs, "usr/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3")
        if (!ld.exists()) return false
        val bytes = ld.readBytes()
        val from = "__vdso_clock_gettime64\u0000".toByteArray()
        val to = "__vdso_clock_gettimeNO\u0000".toByteArray()
        val at = indexOf(bytes, from)
        if (at < 0) return false
        to.copyInto(bytes, at)
        val tmp = File(ld.parentFile, ld.name + ".new")
        tmp.writeBytes(bytes)
        tmp.setExecutable(true, false)
        check(tmp.renameTo(ld)) { "could not replace ld.so" }
        return true
    }

    private fun download(url: String, dest: File, log: (String) -> Unit) {
        var conn = URL(url).openConnection() as HttpURLConnection
        var redirects = 0
        while (conn.responseCode in 300..399 && redirects++ < 5) {
            val next = conn.getHeaderField("Location")
            conn.disconnect()
            conn = URL(next).openConnection() as HttpURLConnection
        }
        check(conn.responseCode == 200) { "HTTP ${conn.responseCode} for $url" }
        val total = conn.contentLengthLong
        var done = 0L
        var lastPct = -1
        conn.inputStream.use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct / 10 != lastPct / 10) { lastPct = pct; log("  $pct%") }
                    }
                }
            }
        }
    }

    private fun extractTarXz(archive: File, dest: File, stripPrefix: String, log: (String) -> Unit) {
        var count = 0
        TarArchiveInputStream(XZCompressorInputStream(archive.inputStream().buffered())).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = entry.name.removePrefix(stripPrefix).trimEnd('/')
                if (name.isEmpty() || name.split('/').contains("..")) continue
                val out = File(dest, name)
                when {
                    entry.isDirectory -> out.mkdirs()
                    entry.isSymbolicLink -> {
                        out.parentFile?.mkdirs()
                        out.delete()
                        Os.symlink(entry.linkName, out.absolutePath)
                    }
                    entry.isLink -> {
                        out.parentFile?.mkdirs()
                        File(dest, entry.linkName.removePrefix(stripPrefix)).copyTo(out, overwrite = true)
                    }
                    entry.isFile -> {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it) }
                        if (entry.mode and 0b001_001_001 != 0) out.setExecutable(true, false)
                    }
                    // Device nodes and FIFOs: proot binds the host's /dev instead.
                }
                if (++count % 1000 == 0) log("  $count entries")
            }
        }
        log("  $count entries extracted")
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /** Debian 12 rootfs for the ABI this process runs as. */
    private data class Rootfs(val url: String, val sha256: String, val topDir: String)

    private val rootfsSpec: Rootfs
        get() = if (android.os.Process.is64Bit()) ROOTFS_ARM64 else ROOTFS_ARMHF

    companion object {
        private val ROOTFS_ARMHF = Rootfs(
            url = "https://github.com/termux/proot-distro/releases/download/v4.17.3/debian-bookworm-arm-pd-v4.17.3.tar.xz",
            sha256 = "85861ab139d4042302796cf46a93a9efbcb4808c06f7a1ae5fb71812f4564424",
            topDir = "debian-bookworm-arm/",
        )
        private val ROOTFS_ARM64 = Rootfs(
            url = "https://github.com/termux/proot-distro/releases/download/v4.17.3/debian-bookworm-aarch64-pd-v4.17.3.tar.xz",
            sha256 = "3a841a794ae5999b33e33b329582ed0379d4f54ca62c6ce5a8eb9cff5ef8900b",
            topDir = "debian-bookworm-aarch64/",
        )

        private val FAKE_PROC = mapOf(
            "loadavg" to "0.12 0.07 0.02 2/165 765\n",
            "stat" to "cpu  1957 0 2877 93280 262 342 254 87 0 0\n" +
                "cpu0 31 0 226 12027 82 10 4 9 0 0\n" +
                "intr 63361 0 0 0 0 0 0 0 0 0\nctxt 38014093\nbtime 1694292441\n" +
                "processes 26442\nprocs_running 1\nprocs_blocked 0\n",
            "uptime" to "124.08 932.80\n",
            "vmstat" to "nr_free_pages 1743136\nnr_zone_inactive_anon 179281\n",
        )
    }
}

/** Example programs: shown to the model in the system prompt, and parsed by `npm run check`. */
export const examples = [
  `root = Lesson([intro, quiz])
intro = Text("Zippers lock because every tooth hooks into the next. Check what stuck:")
quiz = Quiz("Zippers", [q1, q2])
q1 = MultipleChoice("What holds two zipper teeth together?", ["Magnets", "A bump on one tooth seated in the hollow of the next", "Glue on the tape"], 1, "Each tooth has a bump on top and a hollow underneath; the slider presses one into the other.")
q2 = MultipleChoice("What does the slider's Y-shaped channel do?", ["Heats the teeth", "Guides the two rows together at the right angle", "Counts the teeth"], 1, "The channel brings the rows in at an angle so each bump can slip into the next hollow.")`,
  `root = Lesson([deck])
deck = Flashcards("TCP basics", [c1, c2, c3])
c1 = Card("SYN", "The first packet of the handshake: the client asks to open a connection.")
c2 = Card("ACK", "Acknowledges data received, by the next byte number expected.")
c3 = Card("Window", "How many bytes the receiver can take before the sender must wait.")`,
  `root = Lesson([how, tip])
how = StepExplainer("How a pin tumbler lock opens", [s1, s2, s3])
s1 = Step("Pins at rest", "Spring-loaded pin stacks cross the gap between plug and housing, so the plug can't turn.")
s2 = Step("The key lifts", "Each cut of the key lifts its stack by a different amount.")
s3 = Step("Shear line", "When every gap between key pin and driver pin meets the shear line, the plug turns.")
tip = Callout("A wrong key leaves at least one pin across the shear line.", "tip")`,
];

/** Programs from the app's fake server; they must keep rendering. */
export const fakeServerPrograms = [
  `root = Quiz("Zippers", [q1, q2, q3])
q1 = MultipleChoice("What holds two zipper teeth together?", ["Magnets", "A bump on one tooth seated in the hollow of the next", "Glue on the tape", "Friction alone"], 1, "Each tooth has a bump on top and a hollow underneath; the slider presses one into the other.")
q2 = MultipleChoice("What does the slider's Y-shaped channel do?", ["Heats the teeth", "Guides the two rows together at the right angle", "Cuts loose threads", "Counts the teeth"], 1, "The channel brings the rows in at an angle so each bump can slip into the next hollow.")
q3 = MultipleChoice("Why doesn't a closed zipper pull apart sideways?", ["The teeth are interlocked in a chain", "The tape is elastic", "The slider is still holding them", "Static electricity"], 0, "Once seated, every tooth is locked by its neighbours on both sides.")`,
];

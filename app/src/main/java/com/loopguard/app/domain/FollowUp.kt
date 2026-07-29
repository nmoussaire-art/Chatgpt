package com.loopguard.app.domain

import com.loopguard.app.data.Loop
import com.loopguard.app.data.Side
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Tone(val label: String, val blurb: String) {
    FRIENDLY(
        "Friendly",
        "Warm and low pressure. Good for a first nudge or someone you rely on repeatedly.",
    ),
    PROFESSIONAL(
        "Professional",
        "Neutral and businesslike. The safe default for organisations.",
    ),
    FIRM(
        "Firm",
        "Names the delay and asks for a dated commitment. Use after two unanswered nudges.",
    ),
    FINAL(
        "Final notice",
        "States consequences and an explicit deadline. Use when the polite route is exhausted.",
    ),
}

/**
 * Generates the follow-up text.
 *
 * Everything is assembled on-device from the loop's own fields; there is no
 * model call and no network, so this works on a plane and in airplane mode.
 */
object FollowUpComposer {

    private val dateFormat = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)

    fun suggestTone(loop: Loop, priority: Priority): Tone {
        val overdue = (PriorityEngine.daysUntilDue(loop) ?: 0) < 0
        return when {
            loop.followUpCount >= 3 && overdue -> Tone.FINAL
            loop.followUpCount >= 2 || (overdue && priority.band == PriorityBand.CRITICAL) -> Tone.FIRM
            priority.band == PriorityBand.LOW -> Tone.FRIENDLY
            else -> Tone.PROFESSIONAL
        }
    }

    fun compose(
        loop: Loop,
        tone: Tone,
        senderName: String = "",
        today: LocalDate = LocalDate.now(),
    ): String {
        val name = loop.counterparty.substringBefore('/').trim().ifBlank { "" }
        val greeting = greeting(name, tone)
        val subject = subjectLine(loop)
        val body = body(loop, tone, today)
        val closing = closing(tone, senderName)

        return buildString {
            append(greeting)
            append("\n\n")
            append(body)
            append("\n\n")
            append(closing)
        }.let { if (tone == Tone.FINAL || tone == Tone.FIRM) "$subject\n\n$it" else it }
    }

    fun subjectLine(loop: Loop): String {
        val ref = loop.reference.takeIf { it.isNotBlank() }?.let { " (ref $it)" }.orEmpty()
        return "Subject: ${loop.title}$ref"
    }

    private fun greeting(name: String, tone: Tone): String = when {
        name.isBlank() && tone == Tone.FRIENDLY -> "Hi there,"
        name.isBlank() -> "Hello,"
        tone == Tone.FRIENDLY -> "Hi $name,"
        tone == Tone.FINAL -> "Dear $name,"
        else -> "Hello $name,"
    }

    private fun body(loop: Loop, tone: Tone, today: LocalDate): String {
        val matter = loop.title.trim().removeSuffix(".")
        val quiet = PriorityEngine.daysSilent(loop, today)
        val daysDue = PriorityEngine.daysUntilDue(loop, today)
        val reference = loop.reference.takeIf { it.isNotBlank() }
        val context = loop.notes.trim().takeIf { it.isNotBlank() }
        val waitingOnThem = loop.sideEnum == Side.THEM

        val parts = mutableListOf<String>()

        // 1. The opening move.
        parts += when (tone) {
            Tone.FRIENDLY ->
                "I hope you are doing well. I wanted to check in about $matter."
            Tone.PROFESSIONAL ->
                "I am following up regarding $matter."
            Tone.FIRM ->
                "I am writing again about $matter, which is still outstanding."
            Tone.FINAL ->
                "This is a final follow-up regarding $matter, which remains unresolved."
        }

        // 2. Reference number, if we have one worth quoting.
        if (reference != null) {
            parts += when (tone) {
                Tone.FRIENDLY -> "For reference, it is under $reference."
                else -> "Reference: $reference."
            }
        }

        // 3. The factual history. This is what makes a firm message land.
        if (waitingOnThem && quiet >= 2) {
            parts += when (tone) {
                Tone.FRIENDLY ->
                    "I know things get busy, and it has been about $quiet days since we last spoke."
                Tone.PROFESSIONAL ->
                    "My last contact on this was $quiet days ago and I have not yet had a reply."
                Tone.FIRM ->
                    "I first raised this $quiet days ago" +
                        (if (loop.followUpCount > 0) " and have followed up ${countWord(loop.followUpCount)} since" else "") +
                        ", without a substantive response."
                Tone.FINAL ->
                    "This matter has now been open for $quiet days" +
                        (if (loop.followUpCount > 0) ", with ${countWord(loop.followUpCount)} on record" else "") +
                        ", and no resolution has been provided."
            }
        }

        // 4. Why it matters.
        if (context != null) {
            parts += when (tone) {
                Tone.FRIENDLY, Tone.PROFESSIONAL -> "Context: $context"
                else -> "For context: $context"
            }
        }

        // 5. The deadline pressure.
        if (daysDue != null) {
            val dueText = loop.due?.format(dateFormat).orEmpty()
            parts += when {
                daysDue < 0 && tone == Tone.FRIENDLY ->
                    "The date I was working towards ($dueText) has now passed, so I would really appreciate an update."
                daysDue < 0 && tone == Tone.PROFESSIONAL ->
                    "The agreed date of $dueText has now passed."
                daysDue < 0 && tone == Tone.FIRM ->
                    "The deadline of $dueText has passed and the delay is now creating real consequences on my side."
                daysDue < 0 ->
                    "The deadline of $dueText has passed. I am no longer able to absorb further delay."
                daysDue <= 3 && tone == Tone.FRIENDLY ->
                    "I need to have this settled by $dueText, so even a quick note would help."
                daysDue <= 3 ->
                    "I need this resolved by $dueText."
                else ->
                    "I am working towards $dueText."
            }
        }

        // 6. The ask. Always specific, always dated.
        parts += when (tone) {
            Tone.FRIENDLY ->
                "Could you let me know where it currently stands when you get a moment?"
            Tone.PROFESSIONAL ->
                "Could you please confirm the current status and the expected next step?"
            Tone.FIRM ->
                "Please confirm by return who is handling this and the date by which it will be completed."
            Tone.FINAL ->
                "Please confirm in writing within the next two working days how and when this will be resolved. " +
                    "If I do not hear back, I will escalate this to the appropriate supervisory contact."
        }

        return parts.joinToString(" ")
    }

    private fun closing(tone: Tone, senderName: String): String {
        val signOff = when (tone) {
            Tone.FRIENDLY -> "Thanks so much,"
            Tone.PROFESSIONAL -> "Thank you,"
            Tone.FIRM -> "Thank you for your prompt attention,"
            Tone.FINAL -> "Regards,"
        }
        return if (senderName.isBlank()) signOff else "$signOff\n$senderName"
    }

    private fun countWord(n: Int): String = when (n) {
        1 -> "once"
        2 -> "twice"
        else -> "$n times"
    }
}

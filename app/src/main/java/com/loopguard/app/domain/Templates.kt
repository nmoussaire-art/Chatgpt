package com.loopguard.app.domain

import com.loopguard.app.data.Categories
import com.loopguard.app.data.Side

/**
 * Pre-shaped loops for the situations this app exists for.
 *
 * A template is not just a title: it carries the responsibility split, the
 * realistic deadline and the "what would future-me need to know" prompt,
 * because those are the three fields people leave empty and then regret.
 */
data class LoopTemplate(
    val id: String,
    val name: String,
    val category: String,
    val titleTemplate: String,
    val side: Side,
    val impact: Int,
    val dueInDays: Int,
    val notesHint: String,
    val counterpartyHint: String,
    val referenceHint: String = "",
)

data class TemplateGroup(
    val title: String,
    val subtitle: String,
    val templates: List<LoopTemplate>,
)

object Templates {

    val GROUPS: List<TemplateGroup> = listOf(
        TemplateGroup(
            "School & education",
            "Admissions, reports and the things schools go quiet about",
            listOf(
                LoopTemplate(
                    "school_admission", "Waiting for an admission decision", Categories.SCHOOL,
                    "Admission decision for ", Side.THEM, 4, 10,
                    "Which year group, which child, and what you cannot book until they answer.",
                    "Admissions office",
                    "Application number",
                ),
                LoopTemplate(
                    "school_documents", "Send documents to the school", Categories.SCHOOL,
                    "Send enrolment documents to ", Side.ME, 4, 3,
                    "Exactly which documents are outstanding, and where the originals are.",
                    "School registrar",
                ),
                LoopTemplate(
                    "school_response", "Waiting for a teacher or head to reply", Categories.SCHOOL,
                    "Reply from ", Side.THEM, 3, 5,
                    "What you asked, and what happens if there is no answer.",
                    "Class teacher",
                ),
                LoopTemplate(
                    "school_fees", "Confirm fees or payment plan", Categories.SCHOOL,
                    "Confirm fee schedule with ", Side.THEM, 4, 7,
                    "Amount, instalment dates and what has already been paid.",
                    "Finance office",
                    "Invoice number",
                ),
            ),
        ),
        TemplateGroup(
            "Medical & insurance",
            "Approvals and results that quietly stall",
            listOf(
                LoopTemplate(
                    "med_preapproval", "Insurance pre-approval", Categories.HEALTH,
                    "Insurance pre-approval for ", Side.THEM, 5, 5,
                    "Treatment, provider, and the appointment date this blocks.",
                    "Insurer",
                    "Policy number",
                ),
                LoopTemplate(
                    "med_results", "Waiting for test results", Categories.HEALTH,
                    "Results for ", Side.THEM, 4, 4,
                    "Which test, which date, and who was meant to call you.",
                    "Clinic",
                    "Patient / file number",
                ),
                LoopTemplate(
                    "med_claim", "Reimbursement claim", Categories.HEALTH,
                    "Reimbursement claim for ", Side.THEM, 4, 21,
                    "Amount claimed, date submitted, and the receipts you attached.",
                    "Insurer",
                    "Claim number",
                ),
                LoopTemplate(
                    "med_referral", "Send documents to a doctor or specialist", Categories.HEALTH,
                    "Send records to ", Side.ME, 4, 3,
                    "Which records, and the appointment they are needed for.",
                    "Specialist",
                ),
            ),
        ),
        TemplateGroup(
            "Property & housing",
            "Landlords, agents and repairs",
            listOf(
                LoopTemplate(
                    "prop_repair", "Repair request to landlord or agent", Categories.PROPERTY,
                    "Repair: ", Side.THEM, 4, 7,
                    "What is broken, when you first reported it, and any photos you took.",
                    "Property manager",
                ),
                LoopTemplate(
                    "prop_quote", "Waiting for a contractor quote", Categories.PROPERTY,
                    "Quote for ", Side.THEM, 3, 7,
                    "Scope of work and the budget you have in mind.",
                    "Contractor",
                ),
                LoopTemplate(
                    "prop_lease", "Lease renewal confirmation", Categories.PROPERTY,
                    "Lease renewal for ", Side.THEM, 5, 30,
                    "Current end date, the notice period, and the rent you agreed verbally.",
                    "Landlord",
                    "Contract number",
                ),
                LoopTemplate(
                    "prop_deposit", "Chase a deposit return", Categories.PROPERTY,
                    "Deposit return from ", Side.THEM, 4, 14,
                    "Amount, move-out date, and the condition report reference.",
                    "Landlord",
                ),
            ),
        ),
        TemplateGroup(
            "Money & finance",
            "The loops that cost real money when forgotten",
            listOf(
                LoopTemplate(
                    "fin_accountant", "Send documents to the accountant", Categories.MONEY,
                    "Send documents to ", Side.ME, 4, 5,
                    "Which period, which statements, and the filing deadline behind it.",
                    "Accountant",
                ),
                LoopTemplate(
                    "fin_refund", "Chase a refund", Categories.MONEY,
                    "Refund for ", Side.THEM, 3, 14,
                    "Amount, order date, and what they promised when you asked.",
                    "Merchant",
                    "Order number",
                ),
                LoopTemplate(
                    "fin_invoice", "Unpaid invoice", Categories.MONEY,
                    "Unpaid invoice: ", Side.THEM, 4, 14,
                    "Amount, due date, and the payment terms you agreed.",
                    "Client",
                    "Invoice number",
                ),
                LoopTemplate(
                    "fin_dispute", "Dispute an incorrect charge", Categories.MONEY,
                    "Dispute charge from ", Side.THEM, 4, 10,
                    "Amount, statement date, and why it is wrong.",
                    "Bank",
                    "Transaction reference",
                ),
                LoopTemplate(
                    "fin_payment", "Complete a payment", Categories.MONEY,
                    "Pay ", Side.ME, 4, 3,
                    "Amount, payee details, and what happens if it is late.",
                    "Payee",
                ),
            ),
        ),
        TemplateGroup(
            "Government & admin",
            "Applications that expire silently",
            listOf(
                LoopTemplate(
                    "admin_visa", "Visa or residency application", Categories.ADMIN,
                    "Visa application for ", Side.THEM, 5, 21,
                    "Submission date, biometrics status, and the travel date this blocks.",
                    "Immigration office",
                    "Application number",
                ),
                LoopTemplate(
                    "admin_renewal", "Licence or document renewal", Categories.ADMIN,
                    "Renew ", Side.ME, 4, 14,
                    "Expiry date and what you cannot legally do once it lapses.",
                    "Issuing authority",
                    "Document number",
                ),
                LoopTemplate(
                    "admin_certificate", "Request an official certificate", Categories.ADMIN,
                    "Request certificate: ", Side.THEM, 3, 14,
                    "Which certificate, and what it is needed for.",
                    "Authority",
                    "Request number",
                ),
                LoopTemplate(
                    "admin_form", "Complete and submit a form", Categories.ADMIN,
                    "Submit form: ", Side.ME, 3, 5,
                    "Where the form is, what is still missing, and the submission deadline.",
                    "Authority",
                ),
            ),
        ),
        TemplateGroup(
            "Work & commitments",
            "Promises in both directions",
            listOf(
                LoopTemplate(
                    "work_approval", "Waiting for an approval", Categories.WORK,
                    "Approval for ", Side.THEM, 4, 5,
                    "Who has to sign, and what is blocked until they do.",
                    "Approver",
                ),
                LoopTemplate(
                    "work_promise", "Someone promised you something", Categories.WORK,
                    "Promised: ", Side.THEM, 3, 7,
                    "Exactly what was promised, when, and in which conversation.",
                    "Who promised",
                ),
                LoopTemplate(
                    "work_deliverable", "You owe someone a deliverable", Categories.WORK,
                    "Deliver ", Side.ME, 4, 5,
                    "What done looks like, and the agreed date.",
                    "Recipient",
                ),
            ),
        ),
    )

    val ALL: List<LoopTemplate> = GROUPS.flatMap { it.templates }

    fun byId(id: String): LoopTemplate? = ALL.firstOrNull { it.id == id }
}

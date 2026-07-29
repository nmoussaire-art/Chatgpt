package com.deadlineguardian.engine

import com.deadlineguardian.data.ItemKind

/**
 * Decides what was photographed by keyword scoring.
 *
 * Deliberately not a machine-learning model: receipts vary wildly by country and the
 * user can always correct the guess on the review screen, so a transparent scorer that
 * never needs training data or network beats a black box here.
 */
object Classifier {

    private val SIGNALS: Map<ItemKind, List<Pair<String, Int>>> = mapOf(
        ItemKind.MEDICINE to listOf(
            "comprime" to 3, "comprimé" to 3, "gelule" to 3, "gélule" to 3,
            "tablet" to 2, "capsule" to 2, "posologie" to 3, "dosage" to 2,
            "pharmac" to 3, "ordonnance" to 3, "prescription" to 3, "mg" to 1,
            "ml" to 1, "sirop" to 2, "syrup" to 2, "notice" to 1, "lot" to 1,
            "batch" to 1, "excipient" to 3, "voie orale" to 3, "sachet" to 1
        ),
        ItemKind.FOOD to listOf(
            "consommer" to 3, "best before" to 3, "dlc" to 3, "dluo" to 3,
            "use by" to 3, "conserver" to 2, "réfrigér" to 2, "refriger" to 2,
            "ingredients" to 2, "ingrédients" to 2, "kcal" to 2, "poids net" to 2,
            "net weight" to 2, "allerg" to 2
        ),
        ItemKind.DOCUMENT to listOf(
            "passport" to 4, "passeport" to 4, "carte nationale" to 4, "cin" to 3,
            "identity" to 3, "identité" to 3, "permis" to 3, "licence" to 2,
            "license" to 2, "visa" to 3, "assurance" to 3, "insurance" to 3,
            "police n" to 2, "residence" to 2, "séjour" to 3, "sejour" to 3,
            "date of expiry" to 3, "date d'expiration" to 3, "carte grise" to 4,
            "vignette" to 3, "contrat" to 2, "contract" to 2
        ),
        ItemKind.WARRANTY to listOf(
            "warranty" to 4, "garantie" to 4, "guarantee" to 4, "bon de garantie" to 5,
            "warranty card" to 5, "serial" to 2, "s/n" to 2, "imei" to 2,
            "numero de serie" to 2, "numéro de série" to 2, "sav" to 2
        ),
        ItemKind.SUBSCRIPTION to listOf(
            "subscription" to 4, "abonnement" to 4, "renew" to 3, "renouvel" to 3,
            "monthly plan" to 3, "billing" to 2, "facturation" to 2, "auto-renew" to 4
        ),
        ItemKind.RECEIPT to listOf(
            "total" to 2, "sous-total" to 2, "subtotal" to 2, "tva" to 2, "vat" to 2,
            "ticket" to 3, "caisse" to 3, "facture" to 3, "invoice" to 3,
            "receipt" to 3, "montant" to 2, "espece" to 2, "espèce" to 2,
            "carte bancaire" to 2, "cash" to 1, "qte" to 1, "qty" to 1,
            "merci de votre visite" to 3, "thank you for your" to 2, "ttc" to 2,
            "net a payer" to 3, "net à payer" to 3, "rendu" to 2, "change due" to 2
        )
    )

    /** Electronics get a longer statutory warranty, so it's worth detecting separately. */
    private val ELECTRONICS = listOf(
        "laptop", "ordinateur", "pc ", "macbook", "iphone", "samsung", "xiaomi",
        "smartphone", "telephone", "téléphone", "tv ", "television", "télévision",
        "casque", "headphone", "earbud", "airpod", "tablet", "tablette", "ipad",
        "console", "playstation", "xbox", "camera", "caméra", "appareil photo",
        "refrigerateur", "réfrigérateur", "lave-linge", "washing machine",
        "microwave", "micro-onde", "climatiseur", "imprimante", "printer",
        "montre connect", "smartwatch", "ecran", "écran", "monitor", "clavier"
    )

    data class Result(val kind: ItemKind, val confidence: Float, val isElectronics: Boolean)

    fun classify(text: String): Result {
        val lower = text.lowercase()

        val scores = SIGNALS.mapValues { (_, signals) ->
            signals.sumOf { (needle, weight) -> if (lower.contains(needle)) weight else 0 }
        }

        val best = scores.maxByOrNull { it.value }
        val total = scores.values.sum()
        val isElectronics = ELECTRONICS.any { lower.contains(it) }

        if (best == null || best.value == 0) {
            return Result(ItemKind.OTHER, 0f, isElectronics)
        }

        // Confidence is how dominant the winner is, not its raw score — a receipt that
        // also mentions "garantie" should surface as a low-confidence guess so the
        // review screen nudges the user to check it.
        val confidence = if (total == 0) 0f else best.value.toFloat() / total.toFloat()
        return Result(best.key, confidence.coerceIn(0f, 1f), isElectronics)
    }
}

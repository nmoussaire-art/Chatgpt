package com.ontimequant.forecast

import kotlin.math.max

/**
 * Conjugate normal-normal shrinkage.
 *
 * Given a parent (prior) estimate `μ₀` with prior strength `κ` expressed as an
 * equivalent number of observations, and a child estimate `x̄` computed from `n_eff`
 * effective observations, the posterior mean is
 *
 * ```
 *              n_eff · x̄  +  κ · μ₀
 *   μ_post  =  ─────────────────────
 *                   n_eff + κ
 * ```
 *
 * and the posterior "credit" (how much of the estimate is genuinely the child's) is
 *
 * ```
 *   w = n_eff / (n_eff + κ)
 * ```
 *
 * This is the standard posterior mean for a normal likelihood with known variance and a
 * normal prior whose variance is `σ²/κ`. One observation on a brand-new route therefore
 * moves the estimate by `1/(1+κ)` of the way — never far enough for sparse personal data
 * to override a credible routing baseline.
 */
data class ShrunkEstimate(
    val value: Double,
    /** Effective sample size backing the child estimate. */
    val effectiveSampleSize: Double,
    /** Fraction of [value] attributable to the child's own data, in `[0, 1]`. */
    val personalWeight: Double,
    val levelName: String,
)

object Shrinkage {

    /** One level of the hierarchy. */
    data class Level(
        val name: String,
        val estimate: Double,
        val effectiveSampleSize: Double,
        /** Prior strength κ: how many observations this level needs before it dominates its parent. */
        val priorStrength: Double,
    )

    /**
     * Fold a hierarchy from the most general level (index 0, e.g. the population prior)
     * to the most specific (last, e.g. route × time-bucket), shrinking each child toward
     * the posterior of its parent.
     *
     * @param root the population prior, treated as certain-enough to anchor the chain.
     */
    fun hierarchical(root: Double, levels: List<Level>): ShrunkEstimate {
        var posterior = root
        var credit = 0.0
        var nEff = 0.0
        var name = "System prior"
        for (level in levels) {
            val n = max(0.0, level.effectiveSampleSize)
            val k = max(1e-9, level.priorStrength)
            val w = n / (n + k)
            posterior = w * level.estimate + (1 - w) * posterior
            // Personal credit accumulates multiplicatively: the deepest level's credit is
            // conditional on the levels above it having already been personalised.
            credit = w + (1 - w) * credit
            if (w > 0.10) {
                name = level.name
                nEff = n
            }
        }
        return ShrunkEstimate(posterior, nEff, credit.coerceIn(0.0, 1.0), name)
    }

    /** Single-step convenience wrapper. */
    fun toward(prior: Double, sampleMean: Double, effectiveSampleSize: Double, priorStrength: Double): Double {
        val n = max(0.0, effectiveSampleSize)
        val k = max(1e-9, priorStrength)
        return (n * sampleMean + k * prior) / (n + k)
    }

    /**
     * Shrinkage for a *scale* parameter. Variances are combined on the precision scale,
     * which is the conjugate operation for a scaled-inverse-χ² prior, then converted back.
     * A sparse sample can therefore widen but not implausibly narrow the prior.
     */
    fun scaleToward(priorSigma: Double, sampleSigma: Double, effectiveSampleSize: Double, priorStrength: Double): Double {
        val n = max(0.0, effectiveSampleSize)
        val k = max(1e-9, priorStrength)
        val priorVar = priorSigma * priorSigma
        val sampleVar = sampleSigma * sampleSigma
        val blended = (n * sampleVar + k * priorVar) / (n + k)
        return kotlin.math.sqrt(max(1e-8, blended))
    }
}

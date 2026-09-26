package com.keyguard.app.ui

import com.keyguard.app.R
import com.keyguard.detect.Category

/**
 * The one place a detection category is turned into words.
 *
 * There were two copies of this `when` before the parent notification needed a third, and two
 * copies of a mapping that decides what a child is told they did wrong is already one too many:
 * the failure mode is not a compile error, it is the overlay and the parent's activity feed
 * quietly describing the same event differently.
 *
 * The labels are written for the child, and the parent side reuses them deliberately rather
 * than keeping a sterner vocabulary of its own. A parent reading "Support" where the child saw
 * "Support" can tell what their child actually saw on screen, which is the whole basis of the
 * conversation the product is supposed to start. A second, blunter set of words for the parent
 * would mean the two ends of a family are shown different accounts of the same moment.
 */
object CategoryLabels {

    fun res(category: Category): Int = when (category) {
        Category.PII_DISCLOSURE -> R.string.category_pii
        Category.HARASSMENT -> R.string.category_harassment
        Category.SEXUAL_SOLICITATION -> R.string.category_solicitation
        Category.SELF_HARM -> R.string.category_self_harm
        Category.VIOLENCE_THREAT -> R.string.category_violence
        Category.IN_PERSON_MEETUP -> R.string.category_meetup
        Category.SUBSTANCE -> R.string.category_substance
    }
}

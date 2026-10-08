package com.gabrielifrim.brut.audio

/** Outils de prise : tous désactivés par défaut, l'enregistrement reste « brut ». */
data class TakeOptions(
    /** Secondes conservées avant l'appui sur REC (0, 2, 5 ou 10). */
    val prerollSeconds: Int = 0,
    /** Second fichier enregistré en parallèle, au gain abaissé de [safetyDb]. */
    val safetyTrack: Boolean = false,
    val safetyDb: Float = -12f,
    /** REC arme la prise ; elle démarre quand le signal dépasse [triggerDb]. */
    val trigger: Boolean = false,
    val triggerDb: Float = -30f,
    /** Écoute de contrôle au casque. */
    val monitor: Boolean = false,
) {
    /** Le déclenchement sur seuil garde toujours au moins une seconde : l'attaque n'est pas perdue. */
    val effectivePrerollSeconds: Int get() = if (trigger) maxOf(prerollSeconds, 1) else prerollSeconds

    companion object {
        val PREROLL_CHOICES = listOf(0, 2, 5, 10)
        val SAFETY_CHOICES = listOf(-6f, -12f, -18f)
        val TRIGGER_CHOICES = listOf(-50f, -40f, -30f, -20f)
    }
}

package com.gabrielifrim.brut.audio

/**
 * Préréglages de terrain : un format et des outils de prise, rien d'autre. Ils ne
 * touchent ni au gain, ni à l'entrée, ni au mode de capture (propres au micro branché),
 * et n'ajoutent aucun traitement : ce sont les mêmes réglages qu'à la main, en un geste.
 */
enum class Preset(val format: AudioFormatSpec, val options: TakeOptions) {
    /** Une voix, parfois un éclat de rire : mono, avec une piste de sécurité. */
    INTERVIEW(
        AudioFormatSpec(48_000, BitDepth.PCM_24, 1),
        TakeOptions(prerollSeconds = 2, safetyTrack = true, safetyDb = -12f),
    ),

    /** Crêtes imprévisibles et début qu'on rate toujours : pré-enregistrement long et sécurité. */
    CONCERT(
        AudioFormatSpec(48_000, BitDepth.PCM_24, 2),
        TakeOptions(prerollSeconds = 5, safetyTrack = true, safetyDb = -12f),
    ),

    /** Sons calmes et détaillés : haute fréquence, stéréo, aucun outil. */
    AMBIANCE(
        AudioFormatSpec(96_000, BitDepth.PCM_24, 2),
        TakeOptions(),
    ),

    /** Lecture au calme : mono, quelques secondes gardées avant REC pour la première respiration. */
    VOICE_OVER(
        AudioFormatSpec(48_000, BitDepth.PCM_24, 1),
        TakeOptions(prerollSeconds = 2),
    );

    /**
     * Ce préréglage décrit-il les réglages actuels ? L'écoute casque n'entre pas en compte :
     * elle dépend du casque branché, pas du type de prise.
     */
    fun matches(format: AudioFormatSpec, options: TakeOptions): Boolean =
        format == this.format && options.normalized() == this.options.normalized()

    /** Options appliquées en gardant l'écoute casque telle que l'utilisateur l'a laissée. */
    fun optionsKeeping(current: TakeOptions): TakeOptions = options.copy(monitor = current.monitor)

    companion object {
        fun matching(format: AudioFormatSpec, options: TakeOptions): Preset? =
            entries.firstOrNull { it.matches(format, options) }

        // Seuils et niveaux désactivés n'ont pas d'importance : on les ramène aux valeurs par défaut.
        private fun TakeOptions.normalized(): TakeOptions = copy(
            safetyDb = if (safetyTrack) safetyDb else TakeOptions().safetyDb,
            triggerDb = if (trigger) triggerDb else TakeOptions().triggerDb,
            monitor = false,
        )
    }
}

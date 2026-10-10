package app.narrio.ui

import app.narrio.domain.Preparation

/** The shelf row's line for a book TorBox is getting ready; null when nothing is being prepared. */
fun shelfPreparationLabel(state: String): String? = when (state) {
    "preparing" -> "Getting ready in TorBox"
    "ready" -> "Ready to listen"
    "failed" -> "Couldn't get it ready · Try another recording"
    "paused" -> "Stopped checking TorBox · Open to check again"
    else -> null
}

/** What the book page's preparation card says under its status. */
fun preparationNote(prep: Preparation, cacheState: String): String = when {
    prep.ready -> "Your source is ready. Choose Listen to begin."
    prep.failed -> "${prep.problem} Try another recording, or check again if you restarted it in TorBox."
    prep.paused -> "${prep.problem} Nothing is lost; TorBox keeps the recording."
    cacheState == "cached" -> "TorBox already has this recording and is adding it to your account. You can leave; Narrio keeps checking and tells you when it's ready."
    else -> "TorBox is getting this recording ready. Nothing downloads to your phone. You can leave; Narrio keeps checking and tells you when it's ready."
}

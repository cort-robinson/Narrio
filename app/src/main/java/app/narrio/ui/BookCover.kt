package app.narrio.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.blur
import app.narrio.R
import app.narrio.domain.Audiobook

/** Covers already decoded this session render immediately, so shared transitions never flash a fallback. */
private val loadedCovers = java.util.Collections.synchronizedSet(HashSet<String>())

@Composable
fun BookCover(book: Audiobook, modifier: Modifier = Modifier, large: Boolean = false, sharedKey: String? = null) {
    val key = book.title.lowercase()
    val palette = when {
        "pride" in key -> Pair(Color(0xFF694851), Color(0xFFEAD7B4))
        "sherlock" in key -> Pair(Color(0xFFD0BB8F), Color(0xFF223632))
        "gatsby" in key -> Pair(Color(0xFF164246), Color(0xFFEEC984))
        "dracula" in key -> Pair(Color(0xFF58282C), Color(0xFFF2D9B6))
        "alice" in key -> Pair(Color(0xFF5B7891), Color(0xFFF4E4BE))
        else -> Pair(Color(0xFF435C4A), Color(0xFFF0E4CC))
    }
    val curated = listOf("secret garden", "pride", "sherlock", "gatsby", "dracula", "alice").any { it in key }
    BoxWithConstraints(Modifier.sharedCover(sharedKey).then(modifier).clip(RoundedCornerShape(8.dp)).background(palette.first)) {
        val tiny = maxWidth < 100.dp
        var coverLoaded by remember(book.coverUrl) { mutableStateOf(book.coverUrl in loadedCovers) }
        val reveal by animateFloatAsState(if (coverLoaded) 1f else 0f, tween(Motion.MEDIUM, easing = Motion.EmphasizedDecelerate), label = "cover")
        val fallback = reveal < 1f
        if (fallback && "secret garden" in key) {
            Image(painterResource(R.drawable.secret_garden), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(0xBD091A13), Color.Transparent, Color(0xD90B1C15)))))
        } else if (fallback) {
            Canvas(Modifier.matchParentSize()) {
                val w = size.width; val h = size.height
                when {
                    "gatsby" in key -> {
                        for (i in 0..5) {
                            val inset = w * (0.12f + i * 0.045f)
                            drawLine(palette.second.copy(alpha = .55f), Offset(inset, h*.52f), Offset(w*.5f, h*.77f+i*h*.028f), 2f)
                            drawLine(palette.second.copy(alpha = .55f), Offset(w-inset, h*.52f), Offset(w*.5f, h*.77f+i*h*.028f), 2f)
                        }
                        drawCircle(palette.second.copy(alpha = .9f), w*.035f, Offset(w*.5f, h*.53f))
                    }
                    "dracula" in key -> {
                        drawCircle(palette.second, w*.28f, Offset(w*.5f, h*.62f))
                        drawCircle(palette.first, w*.24f, Offset(w*.61f, h*.54f))
                        drawRect(Color(0xFF331C25), Offset(0f, h*.8f), Size(w,h*.2f))
                    }
                    "sherlock" in key -> {
                        drawCircle(palette.second.copy(alpha=.85f),w*.22f,Offset(w*.5f,h*.63f),style=androidx.compose.ui.graphics.drawscope.Stroke(w*.05f))
                        drawLine(palette.second,Offset(w*.65f,h*.73f),Offset(w*.83f,h*.86f),w*.07f)
                        drawLine(palette.second.copy(alpha=.3f),Offset(w*.12f,h*.49f),Offset(w*.88f,h*.49f),2f)
                    }
                    "pride" in key -> {
                        drawOval(palette.second.copy(alpha=.65f),Offset(w*.17f,h*.5f),Size(w*.66f,h*.35f),style=androidx.compose.ui.graphics.drawscope.Stroke(2f))
                        drawOval(palette.second.copy(alpha=.45f),Offset(w*.22f,h*.53f),Size(w*.56f,h*.29f),style=androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                        drawLine(palette.second.copy(alpha=.45f),Offset(w*.15f,h*.92f),Offset(w*.85f,h*.92f),1.5f)
                    }
                    else -> {
                        drawCircle(palette.second.copy(alpha=.7f),w*.23f,Offset(w*.65f,h*.67f))
                        drawCircle(palette.first,w*.23f,Offset(w*.37f,h*.61f))
                        drawLine(palette.second.copy(alpha=.5f),Offset(w*.18f,h*.87f),Offset(w*.82f,h*.87f),2f)
                    }
                }
            }
        }
        if (fallback && maxWidth >= 60.dp) Column(Modifier.fillMaxSize().padding(if (large) 22.dp else if (tiny) 6.dp else 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(book.title.replace(Regex(" \\(.*?\\)$"), ""),
                color = if ("secret garden" in key || !curated) Color(0xFFF4E4C9) else palette.second,
                style = if (large) MaterialTheme.typography.headlineLarge else if (tiny) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleLarge,
                maxLines = if (large) 5 else if (tiny) 3 else 4, overflow = TextOverflow.Ellipsis)
            Text(book.author.removePrefix("Sir "), color = if ("secret garden" in key || !curated) Color(0xFFF4E4C9) else palette.second,
                style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (book.coverUrl.isNotBlank()) {
            // One fixed decode size gives every row, detail, and player instance the same memory-cache key,
            // so a cover that is already decoded draws on its first frame (no blank shared-element flight).
            val context = LocalContext.current
            val request = remember(book.coverUrl) { ImageRequest.Builder(context).data(book.coverUrl).size(720).build() }
            val painter = rememberAsyncImagePainter(request,
                onSuccess = { loadedCovers += book.coverUrl; coverLoaded = true }, onError = { coverLoaded = false })
            // Square audiobook art in a book-shaped frame: its own softened light fills the margins.
            if (Build.VERSION.SDK_INT >= 31 && coverLoaded) {
                Image(painter, null, Modifier.matchParentSize().graphicsLayer { alpha = reveal }.blur(18.dp), contentScale = ContentScale.Crop)
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .22f * reveal)))
            }
            Image(painter, "Cover of ${book.title}", Modifier.matchParentSize().graphicsLayer { alpha = reveal }, contentScale = ContentScale.Fit)
        }
    }
}

@Composable
fun NarrioMark(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(30.dp)) {
        val stroke = size.width*.09f
        drawLine(color, Offset(size.width*.18f,size.height*.2f),Offset(size.width*.18f,size.height*.77f),stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width*.18f,size.height*.2f),Offset(size.width*.8f,size.height*.77f),stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width*.8f,size.height*.2f),Offset(size.width*.8f,size.height*.77f),stroke, StrokeCap.Round)
    }
}

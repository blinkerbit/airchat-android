package com.aird.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aird.data.PrivacyPrefs
import com.aird.designsystem.theme.Spacing
import kotlinx.coroutines.launch

private data class OnboardingPage(
    val title: String,
    val body: String,
    val icon: ImageVector,
)

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val pages = listOf(
        OnboardingPage(
            "Chat",
            "Message teammates with end-to-end encryption, bubbles, and quick replies from notifications.",
            Icons.Rounded.Chat,
        ),
        OnboardingPage(
            "Files",
            "Browse shared folders, upload from the share sheet, and drop files straight into Aird.",
            Icons.Rounded.Folder,
        ),
        OnboardingPage(
            "Lists",
            "Keep checklists and polls in sync across chats — pin them to widgets on your home screen.",
            Icons.AutoMirrored.Rounded.ListAlt,
        ),
    )
    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    fun finish() {
        PrivacyPrefs.setOnboardingDone(context)
        onFinished()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(Spacing.xxxl))
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            val item = pages[page]
            Column(
                Modifier.fillMaxSize().padding(horizontal = Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        item.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(44.dp),
                    )
                }
                Spacer(Modifier.height(Spacing.xxl))
                Text(item.title, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(Spacing.md))
                Text(
                    item.body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = Spacing.lg),
            horizontalArrangement = Arrangement.Center,
        ) {
            pages.indices.forEach { i ->
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (i == pager.currentPage) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (i == pager.currentPage) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { finish() }) { Text("Skip") }
            Button(
                onClick = {
                    if (pager.currentPage >= pages.lastIndex) finish()
                    else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                },
            ) {
                Text(if (pager.currentPage >= pages.lastIndex) "Get started" else "Next")
            }
        }
    }
}

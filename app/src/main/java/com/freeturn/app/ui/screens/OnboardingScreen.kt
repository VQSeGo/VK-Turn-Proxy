@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.freeturn.app.R
import com.freeturn.app.ui.HapticUtil
import com.freeturn.app.viewmodel.ProxyViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    viewModel: ProxyViewModel,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val infiniteTransition = rememberInfiniteTransition(label = "icon_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    var token by rememberSaveable { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            ) { focusManager.clearFocus() }
    ) {
        val clipboardManager = LocalClipboardManager.current

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 32.dp)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(40.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .scale(pulseScale)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.nearby_24px),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }

                Spacer(Modifier.height(32.dp))

                Text(
                    text = "Turn Proxy",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Введите ваш токен доступа для авторизации и загрузки конфигурации",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(32.dp))

                OutlinedTextField(
                    value = token,
                    onValueChange = {
                        token = it
                        errorMessage = null
                    },
                    label = { Text("Токен авторизации") },
                    placeholder = { Text("Можно получить в боте") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = errorMessage != null,
                    trailingIcon = {
                        IconButton(onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            val clip = clipboardManager.getText()?.text.orEmpty()
                            if (clip.isNotBlank()) {
                                token = clip
                                errorMessage = null
                            }
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.content_paste_24px),
                                contentDescription = "Вставить"
                            )
                        }
                    },
                    supportingText = {
                        if (errorMessage != null) {
                            Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                        }
                    },
                    enabled = !isLoading
                )
            }

            Spacer(Modifier.height(48.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isLoading) {
                    CircularWavyProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Авторизация на сервере...", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Button(
                        onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            val cleanedToken = token.trim()
                            if (cleanedToken.isBlank()) {
                                errorMessage = "Токен не может быть пустым"
                                return@Button
                            }
                            
                            isLoading = true
                            coroutineScope.launch {
                                viewModel.saveAuthToken(cleanedToken)
                                val result = viewModel.fetchAndDecryptConfig(cleanedToken)
                                if (result.isSuccess) {
                                    isLoading = false
                                    onSuccess()
                                } else {
                                    viewModel.saveAuthToken("") // Rollback token on failure
                                    isLoading = false
                                    errorMessage = result.exceptionOrNull()?.message ?: "Ошибка авторизации"
                                    HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_OFF)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        enabled = token.isNotBlank()
                    ) {
                        Icon(painterResource(R.drawable.visibility_24px), contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("Авторизоваться", style = MaterialTheme.typography.labelLarge)
                    }
                }

                Spacer(Modifier.height(48.dp))
            }
        }
    }
}

package app.ghostly.ui.components

import androidx.compose.runtime.Composable

/**
 * System back inside the app (Android gesture / button). [onProgress] follows the predictive back
 * gesture (0..1) so the screen can show where back leads before the finger is released; the
 * innermost enabled handler wins. Desktop and iOS have no system back — a no-op there.
 */
@Composable
expect fun PlatformBackHandler(
    enabled: Boolean,
    onProgress: (Float) -> Unit = {},
    onCancel: () -> Unit = {},
    onBack: () -> Unit,
)

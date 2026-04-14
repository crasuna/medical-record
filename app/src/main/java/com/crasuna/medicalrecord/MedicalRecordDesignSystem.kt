package com.crasuna.medicalrecord

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val MedicalRecordLightColorScheme = lightColorScheme(
    primary = Color(0xFF1F6B5C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCEFE8),
    onPrimaryContainer = Color(0xFF103A31),
    secondary = Color(0xFF5B746D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1ECE7),
    onSecondaryContainer = Color(0xFF1C2C28),
    tertiary = Color(0xFF8B6D46),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3E6D2),
    onTertiaryContainer = Color(0xFF34210A),
    background = Color(0xFFF2F6F3),
    onBackground = Color(0xFF17211E),
    surface = Color(0xFFFBFCFA),
    onSurface = Color(0xFF17211E),
    surfaceVariant = Color(0xFFE3ECE8),
    onSurfaceVariant = Color(0xFF54635E),
    outline = Color(0xFFB5C5BF),
    outlineVariant = Color(0xFFD3DFD9),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val MedicalRecordShapes = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Immutable
data class MedicalRecordSpacing(
    val xs: Int = 4,
    val sm: Int = 8,
    val md: Int = 12,
    val lg: Int = 16,
    val xl: Int = 20,
    val xxl: Int = 24,
    val section: Int = 28,
)

private val LocalMedicalRecordSpacing = staticCompositionLocalOf { MedicalRecordSpacing() }

val MaterialTheme.medicalSpacing: MedicalRecordSpacing
    @Composable
    @ReadOnlyComposable
    get() = LocalMedicalRecordSpacing.current

private fun medicalRecordTypography(): Typography {
    return Typography(
        headlineLarge = TextStyle(
            fontSize = 30.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
        ),
        headlineMedium = TextStyle(
            fontSize = 24.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.2).sp,
        ),
        titleLarge = TextStyle(
            fontSize = 22.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        titleMedium = TextStyle(
            fontSize = 18.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        titleSmall = TextStyle(
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.1.sp,
        ),
        bodyLarge = TextStyle(
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Normal,
        ),
        bodyMedium = TextStyle(
            fontSize = 14.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.Normal,
        ),
        bodySmall = TextStyle(
            fontSize = 12.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Normal,
        ),
        labelLarge = TextStyle(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        labelMedium = TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.2.sp,
        ),
        labelSmall = TextStyle(
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.35.sp,
        ),
    )
}

@Composable
fun MedicalRecordTheme(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalMedicalRecordSpacing provides MedicalRecordSpacing(),
    ) {
        MaterialTheme(
            colorScheme = MedicalRecordLightColorScheme,
            typography = medicalRecordTypography(),
            shapes = MedicalRecordShapes,
            content = content,
        )
    }
}

@Composable
fun MedicalRecordBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFF6FBF8),
                        Color(0xFFF0F5F2),
                        Color(0xFFEAF1EE),
                    ),
                ),
            ),
        content = content,
    )
}

@Composable
fun MedicalRecordSurfaceCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 8.dp,
        tonalElevation = 0.dp,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun MedicalRecordInfoPill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Boolean = false,
) {
    val containerColor = if (accent) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
    }
    val contentColor = if (accent) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun MedicalRecordSectionCard(
    title: String,
    subtitle: String? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    MedicalRecordSurfaceCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (trailing != null) {
                Row(
                    modifier = Modifier.padding(start = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = trailing,
                )
            }
        }
        content()
    }
}

@Composable
fun MedicalRecordHeroCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .padding(14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, contentDescription = null, modifier = Modifier.fillMaxSize())
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                    )
                }
            }
            if (trailing != null) {
                Row(
                    modifier = Modifier.padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = trailing,
                )
            }
        }
    }
}

@Composable
fun MedicalRecordPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun MedicalRecordTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        shape = RoundedCornerShape(20.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

@Composable
fun MedicalRecordEmptyState(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MedicalRecordSurfaceCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(26.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .padding(18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, contentDescription = null, modifier = Modifier.fillMaxSize())
                    }
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicalRecordTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    CenterAlignedTopAppBar(
        modifier = modifier,
        title = {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            if (onNavigateBack != null) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowBack,
                        contentDescription = null,
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.98f),
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = MaterialTheme.colorScheme.onBackground,
        ),
    )
}

@Composable
fun MedicalRecordScreenScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable BoxScope.(PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        topBar = topBar,
        floatingActionButton = floatingActionButton,
    ) { innerPadding ->
        MedicalRecordBackground {
            content(innerPadding)
        }
    }
}

package com.loveluke.medicalrecord.core.designsystem

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.loveluke.medicalrecord.R

@Composable
fun MaxWidthContent(
    modifier: Modifier = Modifier,
    maxWidth: androidx.compose.ui.unit.Dp = MedicalRecordThemeTokens.contentMaxWidth,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = maxWidth),
        ) {
            content()
        }
    }
}

@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    @StringRes labelRes: Int = R.string.loading,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(labelRes),
            modifier = Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun EmptyState(
    @StringRes titleRes: Int,
    @StringRes bodyRes: Int,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.FolderOpen,
    actionLabelRes: Int? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(titleRes),
            modifier = Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(bodyRes),
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabelRes != null && onAction != null) {
            Button(
                onClick = onAction,
                modifier = Modifier.padding(top = 20.dp),
            ) {
                Text(stringResource(actionLabelRes))
            }
        }
    }
}

@Composable
fun ErrorState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    @StringRes titleRes: Int = R.string.error_title,
    @StringRes bodyRes: Int = R.string.error_body,
) {
    EmptyState(
        titleRes = titleRes,
        bodyRes = bodyRes,
        modifier = modifier,
        icon = Icons.Outlined.CloudOff,
        actionLabelRes = R.string.retry,
        onAction = onRetry,
    )
}

val ScreenContentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)

fun LazyListScope.verticalContentSpacer() {
    item { Box(Modifier.padding(bottom = 24.dp)) }
}

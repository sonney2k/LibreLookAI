package com.librelookai.auth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.librelookai.feature.auth.R
import com.librelookai.core.designsystem.R as DsR
import com.librelookai.util.Analytics

@Composable
fun SignInScreen(
    onSignIn: () -> Unit,
    signInErrorCode: Int? = null,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { Analytics.screen("SignIn") }

    val errorMessage = when (signInErrorCode) {
        null -> null
        10   -> stringResource(R.string.sign_in_error_not_registered)   // DEVELOPER_ERROR
        7    -> stringResource(R.string.sign_in_error_network)           // NETWORK_ERROR
        else -> stringResource(R.string.sign_in_error_generic, signInErrorCode)
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Checkroom,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(DsR.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.sign_in_prompt),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (errorMessage != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(32.dp))
        Button(onClick = onSignIn) {
            Text(stringResource(R.string.sign_in_button))
        }
    }
}

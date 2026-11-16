package app.scanova.feature.documents.common

import androidx.annotation.PluralsRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import app.scanova.core.designsystem.component.raisedCardColor
import app.scanova.feature.documents.R

/** A top bar that is a search field: it takes the title's place and opens with the keyboard. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchTopBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    TopAppBar(
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.feature_documents_search_label)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.feature_documents_search_close),
                )
            }
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.feature_documents_search_clear))
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/**
 * The rounded search field at the top of Home. It looks like a card (white on
 * the light scheme), so it reads as something to tap, not as a heading.
 */
@Composable
internal fun SearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(R.string.feature_documents_search_label)) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.feature_documents_search_clear))
                }
            }
        } else {
            null
        },
        singleLine = true,
        shape = CircleShape,
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = raisedCardColor(),
            unfocusedContainerColor = raisedCardColor(),
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

/** The explanation in a delete confirmation: whether the backup copy goes too. */
@PluralsRes
internal fun deletionBodyRes(reachesBackup: Boolean): Int =
    if (reachesBackup) R.plurals.feature_documents_delete_body_backup else R.plurals.feature_documents_delete_body_local

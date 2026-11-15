package app.scanova.feature.tools.pick

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.scanova.core.data.documents.DocumentRepository
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.tools.common.CoverLoader
import app.scanova.feature.tools.common.DocumentList
import app.scanova.feature.tools.common.DocumentPicker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** The library to pick one document from, for tools that work on a single document. */
@HiltViewModel
internal class PickDocumentViewModel @Inject constructor(
    documents: DocumentRepository,
    val covers: CoverLoader,
) : ViewModel() {
    val list = DocumentList(documents, viewModelScope)

    override fun onCleared() = covers.clear()
}

/**
 * Pick one document, then [onPicked] continues with it (e.g. to its text, or
 * to the share sheet). [title] names the tool; [hint] says what happens next.
 */
@Composable
internal fun PickDocumentRoute(
    @StringRes title: Int,
    @StringRes hint: Int,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onPicked: (documentId: String) -> Unit,
    viewModel: PickDocumentViewModel = hiltViewModel(),
) {
    val listState by viewModel.list.state.collectAsStateWithLifecycle()
    val query by viewModel.list.query.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScanovaTopBar(title = stringResource(title), onBack = onBack) },
    ) { padding ->
        DocumentPicker(
            state = listState,
            query = query,
            onQueryChange = viewModel.list::onQueryChange,
            onDocumentClick = { onPicked(it.id) },
            loadCover = viewModel.covers::cover,
            onScan = onScan,
            onRetry = viewModel.list::onRetry,
            contentPadding = PaddingValues(Spacing.lg),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            header = {
                item(key = "hint") {
                    Text(
                        text = stringResource(hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                    )
                }
            },
        )
    }
}

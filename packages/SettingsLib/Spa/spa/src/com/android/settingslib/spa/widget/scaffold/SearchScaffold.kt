/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settingslib.spa.widget.scaffold

import androidx.activity.compose.BackHandler
import androidx.appcompat.R
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.settingslib.spa.framework.compose.hideKeyboardAction
import com.android.settingslib.spa.framework.compose.horizontalValues
import com.android.settingslib.spa.framework.theme.SettingsOpacity
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel

/**
 * A [Scaffold] which content is can be full screen, and with a search feature built-in.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SearchScaffold(
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    dpadNavigation: Boolean = false,
    content: @Composable (bottomPadding: Dp, searchQuery: () -> String) -> Unit,
) {
    ActivityTitle(title)
    var isSearchMode by rememberSaveable { mutableStateOf(false) }
    val viewModel: SearchScaffoldViewModel = viewModel()
    val focusManager = LocalFocusManager.current
    val contentFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    var restoreSearchFocus by remember { mutableStateOf(false) }
    val navigationIconModifier = if (dpadNavigation) {
        Modifier.focusProperties { canFocus = false }
    } else Modifier
    val toolbarModifier = if (dpadNavigation) {
        Modifier.focusProperties { down = contentFocus }
    } else Modifier

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SearchableTopAppBar(
                title = title,
                actions = {
                    if (dpadNavigation) Row(toolbarModifier) { actions() } else actions()
                },
                navigationIconModifier = navigationIconModifier,
                searchModifier = if (dpadNavigation) {
                    Modifier.focusRequester(searchFocus).then(toolbarModifier)
                } else Modifier,
                onSearchDown = if (dpadNavigation) ({ contentFocus.requestFocus() }) else null,
                scrollBehavior = scrollBehavior,
                isSearchMode = isSearchMode,
                onSearchModeChange = {
                    if (dpadNavigation && !it) restoreSearchFocus = true
                    isSearchMode = it
                },
                onSearchButtonShown = {
                    if (restoreSearchFocus) {
                        focusManager.clearFocus(force = true)
                        searchFocus.requestFocus()
                        restoreSearchFocus = false
                    }
                },
                searchQuery = viewModel.searchQuery,
                onSearchQueryChange = { viewModel.searchQuery = it },
            )
        },
    ) { paddingValues ->
        Box(
            Modifier
                .padding(paddingValues.horizontalValues())
                .padding(top = paddingValues.calculateTopPadding())
                .then(if (dpadNavigation) {
                    Modifier.focusRequester(contentFocus).focusProperties {
                        exit = { direction ->
                            if (direction == FocusDirection.Up) searchFocus
                            else FocusRequester.Default
                        }
                    }.focusGroup()
                } else Modifier.focusable())
                .fillMaxSize()
        ) {
            content(paddingValues.calculateBottomPadding()) {
                if (isSearchMode) viewModel.searchQuery.text else ""
            }
        }
    }
}

internal class SearchScaffoldViewModel : ViewModel() {
    // Put in view model because TextFieldValue has not default Saver for rememberSaveable.
    var searchQuery by mutableStateOf(TextFieldValue())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchableTopAppBar(
    title: String,
    actions: @Composable RowScope.() -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    isSearchMode: Boolean,
    onSearchModeChange: (Boolean) -> Unit,
    searchQuery: TextFieldValue,
    onSearchQueryChange: (TextFieldValue) -> Unit,
    navigationIconModifier: Modifier,
    searchModifier: Modifier,
    onSearchDown: (() -> Unit)?,
    onSearchButtonShown: () -> Unit,
) {
    if (isSearchMode) {
        SearchTopAppBar(
            query = searchQuery,
            onQueryChange = onSearchQueryChange,
            onClose = { onSearchModeChange(false) },
            actions = actions,
            navigationIconModifier = navigationIconModifier,
            searchModifier = searchModifier,
            onSearchDown = onSearchDown,
        )
    } else {
        SettingsTopAppBar(title, scrollBehavior, navigationIconModifier) {
            SearchAction(searchModifier) {
                scrollBehavior.collapse()
                onSearchQueryChange(TextFieldValue())
                onSearchModeChange(true)
            }
            actions()
        }
        // The toolbar is subcomposed by Scaffold; wait until its button is attached.
        LaunchedEffect(Unit) {
            withFrameNanos { }
            onSearchButtonShown()
        }
    }
}

@Composable
private fun SearchTopAppBar(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    onClose: () -> Unit,
    navigationIconModifier: Modifier = Modifier,
    searchModifier: Modifier = Modifier,
    onSearchDown: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val searchFocus = remember { FocusRequester() }
    CustomizedTopAppBar(
        title = {
            SearchBox(query, onQueryChange, searchModifier.focusRequester(searchFocus), onSearchDown)
        },
        navigationIcon = { CollapseAction(navigationIconModifier, onClose) },
        actions = {
            if (query.text.isNotEmpty()) {
                ClearAction {
                    onQueryChange(TextFieldValue())
                    if (onSearchDown != null) searchFocus.requestFocus()
                }
            }
            actions()
        },
    )
    BackHandler { onClose() }
}

@Composable
private fun SearchBox(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    onDown: (() -> Unit)? = null,
) {
    val focusRequester = remember { FocusRequester() }
    val textStyle = MaterialTheme.typography.bodyLarge
    val hideKeyboardAction = hideKeyboardAction()
    val focusManager = LocalFocusManager.current
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (onDown != null && event.key == Key.DirectionDown) {
                    if (event.type == KeyEventType.KeyDown) {
                        hideKeyboardAction()
                        onDown()
                    }
                    true
                } else if (onDown != null && event.key == Key.DirectionRight &&
                    query.selection.collapsed && query.selection.end == query.text.length
                ) {
                    // At the end of the query, Right reaches Clear and More options.
                    if (event.type == KeyEventType.KeyDown) {
                        focusManager.moveFocus(FocusDirection.Right)
                    } else true
                } else false
            },
        textStyle = textStyle,
        placeholder = {
            Text(
                text = stringResource(R.string.abc_search_hint),
                modifier = Modifier.alpha(SettingsOpacity.Hint),
                style = textStyle,
            )
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { hideKeyboardAction() }),
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )

    LaunchedEffect(focusRequester) {
        // Replacing a focused toolbar schedules focus invalidation after composition. Let
        // that finish before requesting focus on the newly attached text field.
        withFrameNanos { }
        if (onDown != null) focusManager.clearFocus(force = true)
        focusRequester.requestFocus()
    }
}

@Preview
@Composable
private fun SearchTopAppBarPreview() {
    SettingsTheme {
        SearchTopAppBar(query = TextFieldValue(), onQueryChange = {}, onClose = {}) {}
    }
}

@Preview
@Composable
private fun SearchScaffoldPreview() {
    SettingsTheme {
        SearchScaffold(title = "App notifications") { _, _ ->
            Column {
                Preference(object : PreferenceModel {
                    override val title = "Item 1"
                })
                Preference(object : PreferenceModel {
                    override val title = "Item 2"
                })
            }
        }
    }
}

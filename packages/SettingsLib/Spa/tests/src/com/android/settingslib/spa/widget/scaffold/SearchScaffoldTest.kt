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

import android.content.Context
import androidx.appcompat.R
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SearchScaffoldTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun initialState_titleIsDisplayed() {
        composeTestRule.setContent {
            SearchScaffold(title = TITLE) { _, _ -> }
        }

        composeTestRule.onNodeWithText(TITLE).assertIsDisplayed()
    }

    @Test
    fun initialState_clearButtonNotExist() {
        setContent()

        onClearButton().assertDoesNotExist()
    }

    @Test
    fun initialState_searchQueryIsEmpty() {
        val searchQuery = setContent()

        assertThat(searchQuery()).isEqualTo("")
    }

    @Test
    fun canEnterSearchMode() {
        val searchQuery = setContent()

        clickSearchButton()

        composeTestRule.onNodeWithText(TITLE).assertDoesNotExist()
        onSearchHint().assertIsDisplayed()
        onClearButton().assertDoesNotExist()
        assertThat(searchQuery()).isEqualTo("")
    }

    @Test
    fun canExitSearchMode() {
        val searchQuery = setContent()

        clickSearchButton()
        composeTestRule.onNodeWithContentDescription(
            context.getString(R.string.abc_toolbar_collapse_description)
        ).performClick()

        composeTestRule.onNodeWithText(TITLE).assertIsDisplayed()
        onSearchHint().assertDoesNotExist()
        onClearButton().assertDoesNotExist()
        assertThat(searchQuery()).isEqualTo("")
    }

    @Test
    fun canEnterSearchQuery() {
        val searchQuery = setContent()

        clickSearchButton()
        onSearchHint().performTextInput(QUERY)

        onClearButton().assertIsDisplayed()
        assertThat(searchQuery()).isEqualTo(QUERY)
    }

    @Test
    fun canClearSearchQuery() {
        val searchQuery = setContent()

        clickSearchButton()
        onSearchHint().performTextInput(QUERY)
        onClearButton().performClick()

        onClearButton().assertDoesNotExist()
        assertThat(searchQuery()).isEqualTo("")
    }

    @Test
    fun dpad_movesBetweenRowsAndToolbar_withoutFocusingBack() {
        setDpadContent()
        composeTestRule.onNodeWithText("First app").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.onNodeWithText("Second app").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeTestRule.onNodeWithText("First app").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionUp) }
        onSearchButton().assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        composeTestRule.onNodeWithContentDescription(
            context.getString(R.string.abc_action_menu_overflow_description)
        ).assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        onSearchButton().assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        onSearchButton().assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.onNodeWithText("First app").assertIsFocused()
    }

    @Test
    fun dpad_searchCanReturnToResults_andClosingSearchRestoresToolbarFocus() {
        setDpadContent()
        composeTestRule.onNodeWithText("First app")
            .performKeyInput { pressKey(Key.DirectionUp) }
        onSearchButton().performKeyInput { pressKey(Key.DirectionCenter) }
        onSearchHint().assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.onNodeWithText("First app").assertIsFocused()
        composeTestRule.onNodeWithContentDescription(
            context.getString(R.string.abc_toolbar_collapse_description)
        ).performClick()
        onSearchButton().assertIsFocused()
    }

    @Test
    fun dpad_searchQueryCanBeCleared_withoutLosingFocus() {
        setDpadContent()
        composeTestRule.onNodeWithText("First app")
            .performKeyInput { pressKey(Key.DirectionUp) }
        onSearchButton().performKeyInput { pressKey(Key.DirectionCenter) }
        onSearchHint().performTextInput(QUERY)
        composeTestRule.onNodeWithText(QUERY)
            .performKeyInput { pressKey(Key.DirectionRight) }
        onClearButton().assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        onSearchHint().assertIsFocused()
    }

    private fun setDpadContent() {
        composeTestRule.setContent {
            val firstApp = remember { FocusRequester() }
            val inputModeManager = LocalInputModeManager.current
            SearchScaffold(
                title = TITLE,
                dpadNavigation = true,
                actions = { MoreOptionsAction { MenuItem("Show system") {} } },
            ) { _, _ ->
                Column {
                    Button(onClick = {}, modifier = Modifier.focusRequester(firstApp)) {
                        Text("First app")
                    }
                    Button(onClick = {}) { Text("Second app") }
                }
                LaunchedEffect(Unit) {
                    inputModeManager.requestInputMode(InputMode.Keyboard)
                    firstApp.requestFocus()
                }
            }
        }
    }

    private fun onSearchButton() = composeTestRule.onNodeWithContentDescription(
        context.getString(R.string.search_menu_title)
    )

    private fun setContent(): () -> String {
        lateinit var actualSearchQuery: () -> String
        composeTestRule.setContent {
            SearchScaffold(title = TITLE) { _, searchQuery ->
                SideEffect {
                    actualSearchQuery = searchQuery
                }
            }
        }
        return actualSearchQuery
    }

    private fun clickSearchButton() {
        composeTestRule.onNodeWithContentDescription(
            context.getString(R.string.search_menu_title)
        ).performClick()
    }

    private fun onSearchHint() = composeTestRule.onNodeWithText(
        context.getString(R.string.abc_search_hint)
    )

    private fun onClearButton() = composeTestRule.onNodeWithContentDescription(
        context.getString(R.string.abc_searchview_description_clear)
    )

    private companion object {
        const val TITLE = "title"
        const val QUERY = "query"
    }
}

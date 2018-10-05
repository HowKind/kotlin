/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.scratch.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbService
import org.jetbrains.kotlin.idea.KotlinBundle
import org.jetbrains.kotlin.idea.scratch.ScratchFile
import org.jetbrains.kotlin.idea.scratch.ScratchFileLanguageProvider
import org.jetbrains.kotlin.idea.scratch.getScratchPanel
import org.jetbrains.kotlin.idea.scratch.output.ScratchOutputHandlerAdapter
import org.jetbrains.kotlin.idea.scratch.printDebugMessage
import org.jetbrains.kotlin.idea.scratch.ui.ScratchTopPanel
import org.jetbrains.kotlin.idea.scratch.LOG as log

class RunScratchAction : AnAction(
    KotlinBundle.message("scratch.run.button"),
    KotlinBundle.message("scratch.run.button"),
    AllIcons.Actions.Execute
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val scratchPanel = getScratchPanel(e) ?: return

        val scratchFile = scratchPanel.scratchFile
        val psiFile = scratchFile.getPsiFile() ?: return

        val isMakeBeforeRun = scratchPanel.isMakeBeforeRun()
        val isRepl = scratchPanel.isRepl()

        val provider = ScratchFileLanguageProvider.get(psiFile.language) ?: return

        val handler = provider.getOutputHandler()
        handler.onStart(scratchFile)

        log.printDebugMessage("Run Action: isMakeBeforeRun = $isMakeBeforeRun, isRepl = $isRepl")

        val module = scratchPanel.getModule()
        if (module == null) {
            handler.error(scratchFile, "Module should be selected")
            handler.onFinish(scratchFile)
            return
        }

        fun executeScratch() {
            val executor = if (isRepl) provider.createReplExecutor(scratchFile) else provider.createCompilingExecutor(scratchFile)
            if (executor == null) {
                handler.error(scratchFile, "Couldn't run ${psiFile.name}")
                handler.onFinish(scratchFile)
                return
            }

            e.presentation.isEnabled = false

            executor.addOutputHandler(handler)

            executor.addOutputHandler(object : ScratchOutputHandlerAdapter() {
                override fun onFinish(file: ScratchFile) {
                    e.presentation.isEnabled = true
                }
            })

            try {
                executor.execute()
            } catch (ex: Throwable) {
                handler.error(scratchFile, "Exception occurs during Run Scratch Action")
                handler.onFinish(scratchFile)

                e.presentation.isEnabled = true

                log.error(ex)
            }
        }

        if (isMakeBeforeRun) {
            CompilerManager.getInstance(project).make(module) { aborted, errors, _, _ ->
                if (aborted || errors > 0) {
                    handler.error(scratchFile, "There were compilation errors in module ${module.name}")
                }

                if (DumbService.isDumb(project)) {
                    DumbService.getInstance(project).smartInvokeLater {
                        executeScratch()
                    }
                } else {
                    executeScratch()
                }
            }
        } else {
            executeScratch()
        }
    }

    override fun update(e: AnActionEvent) {
        val presentation = e.presentation
        val project = e.project ?: return
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val scratchPanel = TextEditorProvider.getInstance().getTextEditor(editor).getScratchPanel()

        presentation.isVisible = scratchPanel != null
    }

    private fun getScratchPanel(e: AnActionEvent): ScratchTopPanel? {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        return TextEditorProvider.getInstance().getTextEditor(editor).getScratchPanel()
    }
}

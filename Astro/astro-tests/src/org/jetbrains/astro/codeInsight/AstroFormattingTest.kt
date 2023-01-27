package org.jetbrains.astro.codeInsight

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.psi.codeStyle.CodeStyleManager
import org.jetbrains.astro.AstroCodeInsightTestCase

class AstroFormattingTest : AstroCodeInsightTestCase() {

  fun testBasic() = doTest()

  fun testWhitespacesBeforeFrontmatter() = doTest()

  //region Test configuration and helper methods
  override fun getBasePath(): String {
    return "codeInsight/formatting"
  }

  private fun doTest() {
    configure()
    val codeStyleManager = CodeStyleManager.getInstance(project)
    CommandProcessor.getInstance().executeCommand(
      project,
      {
        WriteAction.run<RuntimeException> {
          codeStyleManager.reformat(myFixture.file)
        }
      }, null, null)
    myFixture.checkResultByFile(getTestName(true) + "_after.astro")
  }
  //endregion
}
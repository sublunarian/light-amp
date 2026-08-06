package com.thelightphone.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.USimpleNameReferenceExpression

class ActivityAccessDetector : Detector(), SourceCodeScanner {

    companion object {
        val ISSUE_LOCAL_CONTEXT = Issue.create(
            id = "LightSdkLocalContext",
            briefDescription = "LocalContext.current is not allowed",
            explanation = "Accessing LocalContext.current provides a handle to the Activity, " +
                "which bypasses the Light SDK sandbox. Use LightScreen APIs instead.",
            category = Category.CORRECTNESS,
            priority = 9,
            severity = Severity.ERROR,
            implementation = Implementation(
                ActivityAccessDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )

        val ISSUE_LOCAL_VIEW = Issue.create(
            id = "LightSdkLocalView",
            briefDescription = "LocalView.current is not allowed",
            explanation = "Accessing LocalView.current can provide a path to the Activity context. " +
                "Use LightScreen APIs instead.",
            category = Category.CORRECTNESS,
            priority = 9,
            severity = Severity.ERROR,
            implementation = Implementation(
                ActivityAccessDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )

        val ISSUE_ACTIVITY_CAST = Issue.create(
            id = "LightSdkActivityCast",
            briefDescription = "Casting to Activity is not allowed",
            explanation = "Casting to an Activity type bypasses the Light SDK sandbox. " +
                "Use LightScreen APIs instead.",
            category = Category.CORRECTNESS,
            priority = 9,
            severity = Severity.ERROR,
            implementation = Implementation(
                ActivityAccessDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )

        private val BLOCKED_PROPERTIES = mapOf(
            "current" to mapOf(
                "androidx.compose.ui.platform.LocalContext" to ISSUE_LOCAL_CONTEXT,
                "androidx.compose.ui.platform.LocalView" to ISSUE_LOCAL_VIEW,
            ),
        )

        private val ACTIVITY_TYPES = setOf(
            "android.app.Activity",
            "androidx.activity.ComponentActivity",
            "androidx.appcompat.app.AppCompatActivity",
            "com.thelightphone.sdk.LightActivity",
        )
    }

    override fun getApplicableUastTypes(): List<Class<out UElement>> {
        return listOf(
            UQualifiedReferenceExpression::class.java,
        )
    }

    override fun createUastHandler(context: JavaContext): UElementHandler {
        return object : UElementHandler() {
            override fun visitQualifiedReferenceExpression(node: UQualifiedReferenceExpression) {
                val selector = node.selector
                if (selector is USimpleNameReferenceExpression) {
                    val name = selector.identifier
                    val receiverType = node.receiver.getExpressionType()?.canonicalText

                    // Check LocalContext.current, LocalView.current
                    BLOCKED_PROPERTIES[name]?.forEach { (typeName, issue) ->
                        if (receiverType != null && receiverType.contains(typeName.substringAfterLast("."))) {
                            // Resolve more precisely
                            val resolved = selector.resolve()
                            val containingClass = resolved?.let {
                                (it as? com.intellij.psi.PsiMember)?.containingClass?.qualifiedName
                            }
                            if (containingClass != null && typeName.startsWith(containingClass.substringBefore("Kt"))) {
                                context.report(issue, node, context.getLocation(node), issue.getBriefDescription(com.android.tools.lint.detector.api.TextFormat.TEXT))
                            }
                        }
                    }
                }

                // Check casts to Activity types
                val text = node.asSourceString()
                if (text.contains(" as ") || text.contains(" as? ")) {
                    ACTIVITY_TYPES.forEach { activityType ->
                        val simpleName = activityType.substringAfterLast(".")
                        if (text.contains("as $simpleName") || text.contains("as? $simpleName")) {
                            context.report(
                                ISSUE_ACTIVITY_CAST, node, context.getLocation(node),
                                "Casting to $simpleName is not allowed in Light SDK apps"
                            )
                        }
                    }
                }
            }
        }
    }
}

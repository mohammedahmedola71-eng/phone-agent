package com.phoneagent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("agent", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val keyField = EditText(this).apply {
            hint = "Anthropic API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
        }
        val taskField = EditText(this).apply {
            hint = "What should the agent do? e.g. \"Turn on Do Not Disturb\""
            minLines = 2
        }
        val a11yButton = Button(this).apply {
            text = "1. Enable accessibility service"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val runButton = Button(this)
        val logView = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }
        val scroll = ScrollView(this).apply { addView(logView) }

        runButton.setOnClickListener {
            if (AgentRunner.running.value) {
                AgentRunner.stop()
            } else {
                val key = keyField.text.toString().trim()
                val task = taskField.text.toString().trim()
                if (key.isEmpty() || task.isEmpty()) return@setOnClickListener
                prefs.edit().putString("key", key).apply()
                AgentRunner.start(key, task)
                // Hand the screen over to the agent.
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(keyField, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(a11yButton)
            addView(taskField, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(runButton)
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AgentRunner.log.combine(AgentRunner.running) { l, r -> l to r }.collect { (lines, running) ->
                    logView.text = lines.joinToString("\n")
                    scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                    runButton.text = if (running) "Stop" else "2. Run task"
                }
            }
        }
    }
}

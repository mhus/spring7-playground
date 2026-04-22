package de.mhus.spring7.aiassistant.tools;

import javax.script.ScriptEngine;
import javax.script.ScriptException;

import org.mozilla.javascript.engine.RhinoScriptEngineFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class JavaScriptTool implements AgentTool {

    @Tool(description = """
            Execute JavaScript (Mozilla Rhino) for accurate calculations: math, sorting, filtering,
            aggregating, string processing, date math etc. Prefer this tool over guessing numerical
            or algorithmic answers. The return value of the final expression is returned to you.
            """)
    public String executeJavaScript(
            @ToolParam(description = "JavaScript source. The value of the LAST expression is returned.")
            String code) {
        ScriptEngine engine = new RhinoScriptEngineFactory().getScriptEngine();
        try {
            Object result = engine.eval(code);
            return String.valueOf(result);
        } catch (ScriptException e) {
            return "ERROR: " + e.getMessage();
        }
    }
}

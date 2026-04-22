package de.mhus.spring7.aiassistant.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class JavaScriptTool implements AgentTool {

    private final JsEngine js;

    public JavaScriptTool(JsEngine js) {
        this.js = js;
    }

    @Tool(description = """
            Execute JavaScript for accurate calculations: math, sorting, filtering, aggregating,
            string processing, date math etc. Prefer this tool over guessing numerical or
            algorithmic answers. Uses GraalJS when available (GraalVM runtime), Mozilla Rhino
            as fallback. The return value of the last expression is returned.
            """)
    public String executeJavaScript(
            @ToolParam(description = "JavaScript source. The value of the LAST expression is returned.")
            String code) {
        return js.eval(code);
    }
}

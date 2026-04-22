package de.mhus.spring7.aiassistant.tools;

import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Service;

/**
 * JavaScript evaluator with GraalJS as primary and Mozilla Rhino as fallback.
 * Engine is detected at startup: GraalJS is tried first (via the Polyglot Context API),
 * and if it fails to initialize — because Polyglot isn't on the classpath, no JS language
 * is bundled, or it can't run in the current runtime — Rhino (JSR-223) is used instead.
 */
@Service
public class JsEngine {

    public enum Mode { GRAAL, RHINO, NONE }

    private Mode mode = Mode.NONE;

    @PostConstruct
    void detect() {
        if (tryGraal()) {
            mode = Mode.GRAAL;
            System.out.println("[JsEngine] using GraalJS (polyglot)");
            return;
        }
        if (tryRhino()) {
            mode = Mode.RHINO;
            System.out.println("[JsEngine] using Rhino (GraalJS unavailable)");
            return;
        }
        System.out.println("[JsEngine] no JavaScript engine available");
    }

    public Mode mode() { return mode; }

    public String eval(String code) {
        return switch (mode) {
            case GRAAL -> evalGraal(code);
            case RHINO -> evalRhino(code);
            case NONE -> "ERROR: no JavaScript engine available";
        };
    }

    private boolean tryGraal() {
        try {
            Class.forName("org.graalvm.polyglot.Context");
            try (org.graalvm.polyglot.Context ctx = org.graalvm.polyglot.Context.newBuilder("js").build()) {
                ctx.eval("js", "1+1");
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean tryRhino() {
        try {
            Class.forName("org.mozilla.javascript.engine.RhinoScriptEngineFactory");
            javax.script.ScriptEngine e = new org.mozilla.javascript.engine.RhinoScriptEngineFactory().getScriptEngine();
            e.eval("1+1");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private String evalGraal(String code) {
        try (org.graalvm.polyglot.Context ctx = org.graalvm.polyglot.Context.newBuilder("js")
                .allowAllAccess(false)
                .build()) {
            org.graalvm.polyglot.Value value = ctx.eval("js", code);
            return value == null ? "null" : value.toString();
        } catch (Throwable t) {
            return "ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private String evalRhino(String code) {
        try {
            javax.script.ScriptEngine engine =
                    new org.mozilla.javascript.engine.RhinoScriptEngineFactory().getScriptEngine();
            Object result = engine.eval(code);
            return String.valueOf(result);
        } catch (Throwable t) {
            return "ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }
}

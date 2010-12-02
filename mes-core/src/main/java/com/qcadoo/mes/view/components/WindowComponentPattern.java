package com.qcadoo.mes.view.components;

import com.qcadoo.mes.view.ComponentPattern;
import com.qcadoo.mes.view.ComponentState;
import com.qcadoo.mes.view.ViewComponent;
import com.qcadoo.mes.view.menu.ribbon.Ribbon;
import com.qcadoo.mes.view.patterns.AbstractContainerPattern;

@ViewComponent("window")
public class WindowComponentPattern extends AbstractContainerPattern {

    private static final String JSP_PATH = "newComponents/window.jsp";

    private static final String JS_PATH = "newComponents/window.js";

    private static final String JS_OBJECT = "QCD.components.containers.Window";

    public WindowComponentPattern(final String name, final String fieldPath, final String sourceFieldPath,
            final ComponentPattern parent) {
        super(name, fieldPath, sourceFieldPath, parent);
    }

    @Override
    public ComponentState getComponentStateInstance() {
        return new WindowComponentState();
    }

    public void setRibbon(final Ribbon ribbon) {
        addStaticJavaScriptOption("ribbon", ribbon.getAsJson());
    }

    @Override
    public String getJspFilePath() {
        return JSP_PATH;
    }

    @Override
    public String getJavaScriptFilePath() {
        return JS_PATH;
    }

    @Override
    public String getJavaScriptObjectName() {
        return JS_OBJECT;
    }

}

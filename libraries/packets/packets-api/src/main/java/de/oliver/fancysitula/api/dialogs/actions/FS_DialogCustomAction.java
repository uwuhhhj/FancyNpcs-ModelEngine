package de.oliver.fancysitula.api.dialogs.actions;

import org.intellij.lang.annotations.Subst;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public class FS_DialogCustomAction implements FS_DialogActionButtonAction {

    private String id;
    private Map<String, String> additions;

    public FS_DialogCustomAction(String id, Map<String, String> additions) {
        this.id = id;
        this.additions = additions;
    }

    @Subst("")
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public @Nullable Map<String, String> getAdditions() {
        return additions;
    }

    public void setAdditions(@Nullable Map<String, String> additions) {
        this.additions = additions;
    }
}

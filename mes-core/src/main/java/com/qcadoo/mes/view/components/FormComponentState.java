package com.qcadoo.mes.view.components;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

import com.qcadoo.mes.api.Entity;
import com.qcadoo.mes.internal.DefaultEntity;
import com.qcadoo.mes.model.FieldDefinition;
import com.qcadoo.mes.model.types.HasManyType;
import com.qcadoo.mes.model.validators.ErrorMessage;
import com.qcadoo.mes.view.ComponentState;
import com.qcadoo.mes.view.FieldEntityIdChangeListener;
import com.qcadoo.mes.view.states.AbstractContainerState;

public class FormComponentState extends AbstractContainerState {

    private Long value;

    private boolean valid = true;

    private final Map<String, Object> context = new HashMap<String, Object>();

    private final FormEventPerformer eventPerformer = new FormEventPerformer();

    public FormComponentState() {
        registerEvent("clear", eventPerformer, "clear");
        registerEvent("save", eventPerformer, "save");
        registerEvent("saveAndClear", eventPerformer, "saveAndClear");
        registerEvent("initialize", eventPerformer, "initialize");
        registerEvent("reset", eventPerformer, "initialize");
        registerEvent("delete", eventPerformer, "delete");
    }

    @Override
    public void onFieldEntityIdChange(final Long entityId) {
        setFieldValue(entityId);
        eventPerformer.initialize(new String[0]);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void initializeContent(final JSONObject json) throws JSONException {
        if (json.has("entityId")) {
            // TODO masz
            // value = json.getLong("entityId");
        }
        value = 53L;

        if (json.has("context")) {
            JSONObject contextJson = json.getJSONObject("context");
            Iterator<String> iterator = contextJson.keys();
            while (iterator.hasNext()) {
                String field = iterator.next();
                if ("id".equals(field)) {
                    value = contextJson.getLong(field);
                } else {
                    context.put(field, contextJson.get(field));
                }
            }
        }
    }

    @Override
    public void setFieldValue(final Object value) {
        this.value = (Long) value;
        requestRender();
        requestUpdateState();
        notifyEntityIdChangeListeners((Long) value);
    }

    public boolean isValid() {
        return valid;
    }

    @Override
    public Object getFieldValue() {
        return value;
    }

    @Override
    protected JSONObject renderContent() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("entityId", value);
        return json;
    }

    protected class FormEventPerformer {

        public void saveAndClear(final String[] args) {
            save(args);
            if (isValid()) {
                clear(args);
            }
        }

        public void save(final String[] args) {
            Entity entity = new DefaultEntity(getDataDefinition().getPluginIdentifier(), getDataDefinition().getName(), value);

            copyFieldsToEntity(entity);
            copyContextToEntity(entity);

            System.out.println(entity);

            try {
                entity = getDataDefinition().save(entity);

                if (!entity.isValid()) {
                    valid = false;
                    requestRender();
                    copyMessages(entity.getGlobalErrors());
                }

                copyEntityToFields(entity);

                if (entity.isValid()) {
                    setFieldValue(entity.getId());
                    addMessage("TODO - zapisano", MessageType.SUCCESS); // TODO masz
                } else {
                    addMessage("TODO - niezapisano", MessageType.FAILURE); // TODO masz
                }
            } catch (IllegalStateException e) {
                addMessage("TODO - niezapisano - " + e.getMessage(), MessageType.FAILURE); // TODO masz
            }
        }

        public void delete(final String[] args) {
            if (value != null) {
                try {
                    getDataDefinition().delete(value);
                    addMessage("TODO - usunięto", MessageType.SUCCESS); // TODO masz
                } catch (IllegalStateException e) {
                    addMessage("TODO - nieusunięto - " + e.getMessage(), MessageType.FAILURE); // TODO masz
                }
            } else {
                addMessage("TODO - nieusunięto", MessageType.FAILURE); // TODO masz
            }

            clear(args);
        }

        public void initialize(final String[] args) {
            Entity entity = getFormEntity();

            if (entity != null) {
                copyEntityToFields(entity);
                setFieldValue(entity.getId());
            } else {
                clear(args);
            }
        }

        public void clear(final String[] args) {
            clearFields();
            setFieldValue(null);
        }

        private Entity getFormEntity() {
            if (value != null) {
                return getDataDefinition().get(value);
            } else {
                return null;
            }
        }

        private boolean isValidFormField(final String fieldName) {
            FieldDefinition field = getDataDefinition().getField(fieldName);

            if (field == null || HasManyType.class.isAssignableFrom(field.getType().getClass())) {
                return false;
            }

            return true;
        }

        private void copyEntityToFields(final Entity entity) {
            for (Map.Entry<String, FieldEntityIdChangeListener> field : getFieldEntityIdChangeListeners().entrySet()) {
                if (isValidFormField(field.getKey())) {
                    ErrorMessage message = entity.getError(field.getKey());
                    if (message == null) {
                        ((ComponentState) field.getValue()).setFieldValue(entity.getField(field.getKey()));
                    } else {
                        copyMessage((ComponentState) field.getValue(), message);
                    }
                }
            }
        }

        private void copyMessages(final List<ErrorMessage> messages) {
            for (ErrorMessage message : messages) {
                copyMessage(FormComponentState.this, message);
            }
        }

        private void copyMessage(final ComponentState componentState, final ErrorMessage message) {
            if (message != null) {
                String translation = getTranslationService().translate(message.getMessage(), getLocale());
                componentState.addMessage(translation, MessageType.FAILURE);
            }
        }

        private void clearFields() {
            for (Map.Entry<String, FieldEntityIdChangeListener> field : getFieldEntityIdChangeListeners().entrySet()) {
                if (isValidFormField(field.getKey())) {
                    ((ComponentState) field.getValue()).setFieldValue(null);
                }
            }
        }

        private void copyFieldsToEntity(final Entity entity) {
            for (Map.Entry<String, FieldEntityIdChangeListener> field : getFieldEntityIdChangeListeners().entrySet()) {
                if (isValidFormField(field.getKey())) {
                    entity.setField(field.getKey(), ((ComponentState) field.getValue()).getFieldValue());
                }
            }
        }

        private void copyContextToEntity(final Entity entity) {
            for (Map.Entry<String, Object> field : context.entrySet()) {
                if (isValidFormField(field.getKey())) {
                    entity.setField(field.getKey(), field.getValue());
                }
            }
        }

    }
}

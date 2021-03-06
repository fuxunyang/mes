/**
 * ***************************************************************************
 * Copyright (c) 2010 Qcadoo Limited
 * Project: Qcadoo MES
 * Version: 1.4
 *
 * This file is part of Qcadoo.
 *
 * Qcadoo is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation; either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 * ***************************************************************************
 */
package com.qcadoo.mes.orders.hooks;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.qcadoo.commons.dateTime.DateRange;
import com.qcadoo.localization.api.utils.DateUtils;
import com.qcadoo.mes.basic.ParameterService;
import com.qcadoo.mes.basic.ProductService;
import com.qcadoo.mes.orders.OrderService;
import com.qcadoo.mes.orders.OrderStateChangeReasonService;
import com.qcadoo.mes.orders.TechnologyServiceO;
import com.qcadoo.mes.orders.constants.OrderFields;
import com.qcadoo.mes.orders.constants.OrderType;
import com.qcadoo.mes.orders.constants.OrdersConstants;
import com.qcadoo.mes.orders.constants.ParameterFieldsO;
import com.qcadoo.mes.orders.states.constants.OrderState;
import com.qcadoo.mes.orders.states.constants.OrderStateChangeDescriber;
import com.qcadoo.mes.orders.states.constants.OrderStateChangeFields;
import com.qcadoo.mes.orders.util.OrderDatesService;
import com.qcadoo.mes.states.service.StateChangeEntityBuilder;
import com.qcadoo.mes.technologies.constants.TechnologyFields;
import com.qcadoo.mes.technologies.constants.TechnologyType;
import com.qcadoo.mes.technologies.states.constants.TechnologyState;
import com.qcadoo.model.api.BigDecimalUtils;
import com.qcadoo.model.api.DataDefinition;
import com.qcadoo.model.api.DataDefinitionService;
import com.qcadoo.model.api.Entity;
import com.qcadoo.model.api.FieldDefinition;
import com.qcadoo.model.api.NumberService;
import com.qcadoo.security.api.UserService;
import com.qcadoo.security.constants.UserFields;
import com.qcadoo.view.api.utils.TimeConverterService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class OrderHooks {

    public static final String L_TYPE_OF_PRODUCTION_RECORDING = "typeOfProductionRecording";

    public static final String BACKUP_TECHNOLOGY_PREFIX = "B_";

    public static final long SECOND_MILLIS = 1000;

    public static final List<String> sourceDateFields = Lists.newArrayList("sourceCorrectedDateFrom", "sourceCorrectedDateTo",
            "sourceStartDate", "sourceFinishDate");

    @Autowired
    private DataDefinitionService dataDefinitionService;

    @Autowired
    private NumberService numberService;

    @Autowired
    private ParameterService parameterService;

    @Autowired
    private ProductService productService;

    @Autowired
    private TechnologyServiceO technologyServiceO;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderDatesService orderDatesService;

    @Autowired
    private StateChangeEntityBuilder stateChangeEntityBuilder;

    @Autowired
    private OrderStateChangeDescriber orderStateChangeDescriber;

    @Autowired
    private OrderStateChangeReasonService orderStateChangeReasonService;

    @Autowired
    private UserService userService;

    public boolean validatesWith(final DataDefinition orderDD, final Entity order) {
        boolean isValid = true;

        Entity parameter = parameterService.getParameter();

        isValid = isValid && checkOrderDates(orderDD, order);
        isValid = isValid && checkOrderPlannedQuantity(orderDD, order);
        isValid = isValid && productService.checkIfProductIsNotRemoved(orderDD, order);
        isValid = isValid && checkReasonOfStartDateCorrection(parameter, order);
        isValid = isValid && checkReasonOfEndDateCorrection(parameter, order);
        isValid = isValid && checkEffectiveDeviation(parameter, order);

        return isValid;
    }

    public void onCreate(final DataDefinition orderDD, final Entity order) {
        setInitialState(orderDD, order);
        setCommissionedPlannedQuantity(orderDD, order);
        order.setField(OrderFields.TECHNOLOGY, order.getField(OrderFields.TECHNOLOGY_PROTOTYPE));
    }

    public void onSave(final DataDefinition orderDD, final Entity order) {
        copyStartDate(orderDD, order);
        copyEndDate(orderDD, order);
        copyProductQuantity(orderDD, order);
        onCorrectingTheRequestedVolume(orderDD, order);
        auditDatesChanges(order);
        technologyServiceO.createOrUpdateTechnology(orderDD, order);
        setRemainingQuantity(order);
        setAdditionalFields(order);
    }

    private void setAdditionalFields(final Entity order) {
        if (Objects.isNull(order.getId())) {
            order.setField("includeTpz", parameterService.getParameter().getBooleanField("includeTpzPS"));
            order.setField("includeAdditionalTime", parameterService.getParameter().getBooleanField("includeAdditionalTimePS"));
        }
    }

    public void onCopy(final DataDefinition orderDD, final Entity order) {
        setInitialState(orderDD, order);
        clearOrSetSpecyfiedValueOrderFieldsOnCopy(orderDD, order);
        setProductQuantity(orderDD, order);
        setCopyOfTechnology(order);
    }

    public void setRemainingQuantity(final Entity order) {
        BigDecimal remainingAmountOfProductToProduce = BigDecimalUtils.convertNullToZero(
                order.getDecimalField(OrderFields.PLANNED_QUANTITY)).subtract(
                BigDecimalUtils.convertNullToZero(order.getDecimalField(OrderFields.AMOUNT_OF_PRODUCT_PRODUCED)),
                numberService.getMathContext());
        order.setField(OrderFields.REMAINING_AMOUNT_OF_PRODUCT_TO_PRODUCE, remainingAmountOfProductToProduce);
    }

    public void onDelete(final DataDefinition orderDD, final Entity order) {
        backupTechnology(order);
    }

    public boolean setDateChanged(final DataDefinition dataDefinition, final FieldDefinition fieldDefinition, final Entity order,
            final Object fieldOldValue, final Object fieldNewValue) {
        OrderState orderState = OrderState.of(order);
        if (fieldOldValue != null && fieldNewValue != null && !orderState.equals(OrderState.PENDING)) {

            Date oldDate = DateUtils.parseDate(fieldOldValue);
            Date newDate = DateUtils.parseDate(fieldNewValue);
            if (!oldDate.equals(newDate)) {
                order.setField(OrderFields.DATES_CHANGED, true);
                order.setField(getSourceFieldName(fieldDefinition), fieldOldValue);
            }
        }

        return true;
    }

    private void auditDatesChanges(final Entity order) {
        boolean datesChanged = order.getBooleanField(OrderFields.DATES_CHANGED);
        OrderState orderState = OrderState.of(order);
        if (datesChanged && !orderState.equals(OrderState.PENDING)) {
            order.setField(OrderFields.DATES_CHANGED, false);
            DataDefinition orderStateChangeDD = dataDefinitionService.get(OrdersConstants.PLUGIN_IDENTIFIER,
                    OrdersConstants.MODEL_ORDER_STATE_CHANGE);
            Entity orderStateChange = orderStateChangeDD.create();
            orderStateChange.setField(OrderStateChangeFields.DATES_CHANGED, true);
            orderStateChange.setField(OrderStateChangeFields.ORDER, order);
            orderStateChange.setField(OrderStateChangeFields.SOURCE_CORRECTED_DATE_FROM,
                    order.getField(OrderFields.SOURCE_CORRECTED_DATE_FROM));
            orderStateChange.setField(OrderStateChangeFields.SOURCE_CORRECTED_DATE_TO,
                    order.getField(OrderFields.SOURCE_CORRECTED_DATE_TO));
            orderStateChange.setField(OrderStateChangeFields.SOURCE_FINISH_DATE, order.getField(OrderFields.SOURCE_FINISH_DATE));
            orderStateChange.setField(OrderStateChangeFields.SOURCE_START_DATE, order.getField(OrderFields.SOURCE_START_DATE));

            orderStateChange.setField(OrderStateChangeFields.TARGET_CORRECTED_DATE_FROM,
                    order.getField(OrderFields.CORRECTED_DATE_FROM));
            orderStateChange.setField(OrderStateChangeFields.TARGET_CORRECTED_DATE_TO,
                    order.getField(OrderFields.CORRECTED_DATE_TO));
            orderStateChange.setField(OrderStateChangeFields.TARGET_FINISH_DATE, order.getField(OrderFields.FINISH_DATE));
            orderStateChange.setField(OrderStateChangeFields.TARGET_START_DATE, order.getField(OrderFields.START_DATE));

            orderStateChange.setField(OrderStateChangeFields.SOURCE_STATE, order.getField(OrderFields.STATE));
            orderStateChange.setField(OrderStateChangeFields.TARGET_STATE, order.getField(OrderFields.STATE));
            String workerToChange = order.getStringField(OrderFields.WORKER_TO_CHANGE);
            if (StringUtils.isEmpty(workerToChange)) {
                orderStateChange.setField(OrderStateChangeFields.WORKER,
                        userService.getCurrentUserEntity().getField(UserFields.USER_NAME));
            } else {
                orderStateChange.setField(OrderStateChangeFields.WORKER, workerToChange);
                order.setField(OrderFields.WORKER_TO_CHANGE, null);
            }
            orderStateChange.setField("dateAndTime", setDateToField(new Date()));
            orderStateChange.setField(OrderStateChangeFields.STATUS, "03successful");
            orderStateChangeDD.save(orderStateChange);

        }
    }

    private Object setDateToField(final Date date) {
        return new SimpleDateFormat(DateUtils.L_DATE_TIME_FORMAT, Locale.getDefault()).format(date);
    }

    private String getSourceFieldName(final FieldDefinition fieldDefinition) {
        String targetName = fieldDefinition.getName();
        for (String fieldName : sourceDateFields) {
            if (fieldName.toLowerCase().contains(targetName.toLowerCase())) {
                return fieldName;
            }
        }
        return null;
    }

    private void backupTechnology(final Entity order) {
        Entity technology = order.getBelongsToField(OrderFields.TECHNOLOGY);
        if (technology != null && orderService.isPktEnabled()) {
            String bNumber = BACKUP_TECHNOLOGY_PREFIX + new Date().getTime() + "_"
                    + technology.getStringField(TechnologyFields.NUMBER);
            bNumber = bNumber.substring(0, Math.min(bNumber.length(), 255));
            technology.setField(TechnologyFields.NUMBER, bNumber);
            technology.setField(TechnologyFields.TECHNOLOGY_PROTOTYPE, null);
            technology.getDataDefinition().save(technology);
        }
    }

    public void setInitialState(final DataDefinition orderDD, final Entity order) {
        stateChangeEntityBuilder.buildInitial(orderStateChangeDescriber, order, OrderState.PENDING);
    }

    public boolean checkOrderDates(final DataDefinition orderDD, final Entity order) {
        DateRange orderDateRange = orderDatesService.getCalculatedDates(order);
        Date dateFrom = orderDateRange.getFrom();
        Date dateTo = orderDateRange.getTo();

        if (dateFrom == null || dateTo == null || dateTo.after(dateFrom)) {
            return true;
        }

        order.addError(orderDD.getField(OrderFields.FINISH_DATE), "orders.validate.global.error.datesOrder");

        return false;
    }

    public boolean checkOrderPlannedQuantity(final DataDefinition orderDD, final Entity order) {
        Entity product = order.getBelongsToField(OrderFields.PRODUCT);

        if (product == null) {
            return true;
        }

        BigDecimal plannedQuantity = order.getDecimalField(OrderFields.PLANNED_QUANTITY);

        if (plannedQuantity == null) {
            order.addError(orderDD.getField(OrderFields.PLANNED_QUANTITY), "orders.validate.global.error.plannedQuantityError");

            return false;
        } else {
            return true;
        }
    }

    public void copyStartDate(final DataDefinition orderDD, final Entity order) {
        setStartDate(order);
        fillStartDate(order);
    }

    public void copyEndDate(final DataDefinition orderDD, final Entity order) {
        setEndDate(order);
        fillEndDate(order);
    }

    protected boolean checkReasonOfStartDateCorrection(final Entity parameter, final Entity order) {
        String state = order.getStringField(OrderFields.STATE);
        if (OrderState.ACCEPTED.getStringValue().equals(state)) {
            return !parameter.getBooleanField(ParameterFieldsO.REASON_NEEDED_WHEN_CORRECTING_DATE_FROM)
                    || checkReasonNeeded(order, OrderFields.CORRECTED_DATE_FROM, OrderFields.REASON_TYPES_CORRECTION_DATE_FROM,
                            "orders.order.commentReasonTypeCorrectionDateFrom.isRequired");
        }
        return true;
    }

    protected boolean checkReasonOfEndDateCorrection(final Entity parameter, final Entity order) {
        String orderState = order.getStringField(OrderFields.STATE);
        if (OrderState.ACCEPTED.getStringValue().equals(orderState) || OrderState.IN_PROGRESS.getStringValue().equals(orderState)
                || OrderState.INTERRUPTED.getStringValue().equals(orderState)) {
            return !parameter.getBooleanField(ParameterFieldsO.REASON_NEEDED_WHEN_CORRECTING_DATE_TO)
                    || checkReasonNeeded(order, OrderFields.CORRECTED_DATE_TO, OrderFields.REASON_TYPES_CORRECTION_DATE_TO,
                            "orders.order.commentReasonTypeCorrectionDateTo.isRequired");
        }
        return true;
    }

    private boolean checkReasonNeeded(final Entity order, final String dateFieldName, final String reasonTypeFieldName,
            final String messageTranslationKey) {
        if (order.getField(dateFieldName) != null && order.getHasManyField(reasonTypeFieldName).isEmpty()) {
            order.addError(order.getDataDefinition().getField(reasonTypeFieldName), messageTranslationKey);

            return false;
        }

        return true;
    }

    private boolean checkEffectiveDeviation(final Entity parameter, final Entity order) {
        Long differenceForDateFrom = orderStateChangeReasonService.getEffectiveDateFromDifference(parameter, order);
        Long differenceForDateTo = orderStateChangeReasonService.getEffectiveDateToDifference(parameter, order);
        String orderState = order.getStringField(OrderFields.STATE);
        // EFFECTIVE_DATE_FROM
        if (OrderState.COMPLETED.getStringValue().equals(orderState) || OrderState.ABANDONED.getStringValue().equals(orderState)
                || OrderState.IN_PROGRESS.getStringValue().equals(orderState)
                || OrderState.INTERRUPTED.getStringValue().equals(orderState)) {

            if (parameter.getBooleanField(ParameterFieldsO.REASON_NEEDED_WHEN_DELAYED_EFFECTIVE_DATE_FROM)
                    && differenceForDateFrom > 0L) {
                final String differenceAsString = TimeConverterService.convertTimeToString(String.valueOf(Math
                        .abs(differenceForDateFrom)));

                checkEffectiveDeviationNeeded(order, OrderFields.EFFECTIVE_DATE_FROM,
                        OrderFields.REASON_TYPES_DEVIATIONS_OF_EFFECTIVE_START,
                        "orders.order.reasonNeededWhenDelayedEffectiveDateFrom.isRequired", differenceAsString);
            }
            if (parameter.getBooleanField(ParameterFieldsO.REASON_NEEDED_WHEN_EARLIER_EFFECTIVE_DATE_FROM)
                    && differenceForDateFrom < 0L) {
                final String differenceAsString = TimeConverterService.convertTimeToString(String.valueOf(Math
                        .abs(differenceForDateFrom)));

                checkEffectiveDeviationNeeded(order, OrderFields.EFFECTIVE_DATE_FROM,
                        OrderFields.REASON_TYPES_DEVIATIONS_OF_EFFECTIVE_START,
                        "orders.order.reasonNeededWhenEarlierEffectiveDateFrom.isRequired", differenceAsString);
            }
        }

        // EFFECTIVE_DATE_TO
        if (OrderState.COMPLETED.getStringValue().equals(orderState) || OrderState.ABANDONED.getStringValue().equals(orderState)) {
            if (parameter.getBooleanField(ParameterFieldsO.REASON_NEEDED_WHEN_DELAYED_EFFECTIVE_DATE_TO)
                    && differenceForDateTo > 0L) {
                final String differenceAsString = TimeConverterService.convertTimeToString(String.valueOf(Math
                        .abs(differenceForDateTo)));

                checkEffectiveDeviationNeeded(order, OrderFields.EFFECTIVE_DATE_TO,
                        OrderFields.REASON_TYPES_DEVIATIONS_OF_EFFECTIVE_END,
                        "orders.order.reasonNeededWhenDelayedEffectiveDateTo.isRequired", differenceAsString);
            }
            if (parameter.getBooleanField(ParameterFieldsO.REASON_NEEDED_WHEN_EARLIER_EFFECTIVE_DATE_TO)
                    && differenceForDateTo < 0L) {
                final String differenceAsString = TimeConverterService.convertTimeToString(String.valueOf(Math
                        .abs(differenceForDateTo)));
                checkEffectiveDeviationNeeded(order, OrderFields.EFFECTIVE_DATE_TO,
                        OrderFields.REASON_TYPES_DEVIATIONS_OF_EFFECTIVE_END,
                        "orders.order.reasonNeededWhenEarlierEffectiveDateTo.isRequired", differenceAsString);
            }
        }

        return true;
    }

    private boolean checkEffectiveDeviationNeeded(final Entity order, final String dateFieldName,
            final String reasonTypeFieldName, final String messageTranslationKey, final String differenceAsString) {
        if (order.getField(dateFieldName) != null && order.getHasManyField(reasonTypeFieldName).isEmpty()) {
            order.addError(order.getDataDefinition().getField(reasonTypeFieldName), messageTranslationKey, differenceAsString);

            return false;
        }

        return true;
    }

    private void setStartDate(final Entity order) {
        Long orderId = order.getId();

        if (orderId == null) {
            return;
        }

        Date startDate = order.getDateField(OrderFields.START_DATE);
        if (startDate == null) {
            return;
        }

        Entity orderFromDB = orderService.getOrder(orderId);

        String state = order.getStringField(OrderFields.STATE);

        Date startDateDB = new Date();
        if (orderFromDB.getDateField(OrderFields.START_DATE) != null) {
            startDateDB = orderFromDB.getDateField(OrderFields.START_DATE);
        }
        if (OrderState.PENDING.getStringValue().equals(state) && !startDate.equals(startDateDB)) {
            order.setField(OrderFields.DATE_FROM, startDate);
        }
        if ((OrderState.IN_PROGRESS.getStringValue().equals(state) || OrderState.COMPLETED.getStringValue().equals(state) || OrderState.ABANDONED
                .getStringValue().equals(state)) && !startDate.equals(startDateDB)) {
            order.setField(OrderFields.EFFECTIVE_DATE_FROM, startDate);
        }
        if (OrderState.ACCEPTED.getStringValue().equals(state) && !startDateDB.equals(startDate)) {
            order.setField(OrderFields.CORRECTED_DATE_FROM, startDate);
        }
    }

    private void setEndDate(final Entity order) {
        Long orderId = order.getId();

        if (orderId == null) {
            return;
        }

        Date finishDate = order.getDateField(OrderFields.FINISH_DATE);
        if (finishDate == null) {
            return;
        }

        Entity orderFromDB = orderService.getOrder(orderId);

        String state = order.getStringField(OrderFields.STATE);

        Date finishDateDB = new Date();
        if (orderFromDB.getDateField(OrderFields.FINISH_DATE) != null) {
            finishDateDB = orderFromDB.getDateField(OrderFields.FINISH_DATE);
        }
        if (OrderState.PENDING.getStringValue().equals(state) && !finishDateDB.equals(finishDate)) {
            order.setField(OrderFields.DATE_TO, finishDate);
        }
        if ((OrderState.COMPLETED.getStringValue().equals(state) || OrderState.ABANDONED.getStringValue().equals(state))
                && !finishDateDB.equals(finishDate)) {
            order.setField(OrderFields.EFFECTIVE_DATE_TO, finishDate);
        }
        if ((OrderState.ACCEPTED.getStringValue().equals(state) || OrderState.IN_PROGRESS.getStringValue().equals(state))
                && !finishDateDB.equals(finishDate)) {
            order.setField(OrderFields.CORRECTED_DATE_TO, finishDate);
        }
    }

    private void fillStartDate(final Entity order) {
        DateRange dateRange = orderDatesService.getCalculatedDates(order);

        if (dateRange.getFrom() == null) {
            order.setField(OrderFields.DATE_FROM, order.getField(OrderFields.START_DATE));
        } else {
            order.setField(OrderFields.START_DATE, dateRange.getFrom());
        }
    }

    private void fillEndDate(final Entity order) {
        DateRange dateRange = orderDatesService.getCalculatedDates(order);

        if (dateRange.getTo() == null) {
            order.setField(OrderFields.DATE_TO, order.getField(OrderFields.FINISH_DATE));
        } else {
            order.setField(OrderFields.FINISH_DATE, dateRange.getTo());
        }
    }

    public boolean validateDates(final DataDefinition orderDD, final Entity order) {
        Date effectiveDateFrom = order.getDateField(OrderFields.EFFECTIVE_DATE_FROM);
        Date effectiveDateTo = order.getDateField(OrderFields.EFFECTIVE_DATE_TO);

        if ((effectiveDateFrom != null) && (effectiveDateTo != null) && effectiveDateTo.before(effectiveDateFrom)) {
            order.addError(orderDD.getField(OrderFields.EFFECTIVE_DATE_TO), "orders.validate.global.error.effectiveDateTo");

            return false;
        }

        return true;
    }

    public void copyProductQuantity(final DataDefinition orderDD, final Entity order) {
        setProductQuantity(order);
    }

    public void setProductQuantity(final Entity order) {
        Long orderId = order.getId();

        if (orderId == null) {
            return;
        }

        Entity orderFromDB = orderService.getOrder(orderId);

        BigDecimal plannedQuantity = order.getDecimalField(OrderFields.PLANNED_QUANTITY);
        BigDecimal commissionedPlannedQuantity = order.getDecimalField(OrderFields.COMMISSIONED_PLANNED_QUANTITY);
        BigDecimal commissionedCorrectedQuantity = order.getDecimalField(OrderFields.COMMISSIONED_CORRECTED_QUANTITY);
        BigDecimal plannedQuantityFromDB = orderFromDB.getDecimalField(OrderFields.PLANNED_QUANTITY);

        if (plannedQuantityFromDB.compareTo(plannedQuantity) != 0) {
            String state = order.getStringField(OrderFields.STATE);

            if (OrderState.PENDING.getStringValue().equals(state)) {
                order.setField(OrderFields.COMMISSIONED_PLANNED_QUANTITY, numberService.setScaleWithDefaultMathContext(plannedQuantity));
            }
            if (OrderState.ACCEPTED.getStringValue().equals(state) || OrderState.IN_PROGRESS.getStringValue().equals(state)
                    || OrderState.INTERRUPTED.getStringValue().equals(state)) {
                order.setField(OrderFields.COMMISSIONED_CORRECTED_QUANTITY, numberService.setScaleWithDefaultMathContext(plannedQuantity));
            }
        } else {
            if (BigDecimal.ZERO.compareTo(BigDecimalUtils.convertNullToZero(commissionedCorrectedQuantity)) != 0) {
                order.setField(OrderFields.PLANNED_QUANTITY, numberService.setScaleWithDefaultMathContext(commissionedCorrectedQuantity));
            } else if (BigDecimal.ZERO.compareTo(BigDecimalUtils.convertNullToZero(commissionedPlannedQuantity)) != 0) {
                order.setField(OrderFields.PLANNED_QUANTITY, numberService.setScaleWithDefaultMathContext(commissionedPlannedQuantity));
            }
        }

        BigDecimal doneQuantityFromDB = orderFromDB.getDecimalField(OrderFields.DONE_QUANTITY);
        BigDecimal doneQuantity = order.getDecimalField(OrderFields.DONE_QUANTITY);
        BigDecimal amountOfProductProducedFromDB = orderFromDB.getDecimalField(OrderFields.AMOUNT_OF_PRODUCT_PRODUCED);
        BigDecimal amountOfProductProduced = order.getDecimalField(OrderFields.AMOUNT_OF_PRODUCT_PRODUCED);

        String typeOfProductionRecording = order.getStringField(L_TYPE_OF_PRODUCTION_RECORDING);

        if (StringUtils.isEmpty(typeOfProductionRecording)) {
            if (BigDecimalUtils.convertNullToZero(doneQuantity).compareTo(BigDecimalUtils.convertNullToZero(doneQuantityFromDB)) != 0) {
                order.setField(OrderFields.AMOUNT_OF_PRODUCT_PRODUCED, numberService.setScaleWithDefaultMathContext(doneQuantity));
            } else if (BigDecimalUtils.convertNullToZero(amountOfProductProduced).compareTo(
                    BigDecimalUtils.convertNullToZero(amountOfProductProducedFromDB)) != 0) {
                order.setField(OrderFields.DONE_QUANTITY, numberService.setScaleWithDefaultMathContext(amountOfProductProduced));
            }
        } else {
            order.setField(OrderFields.AMOUNT_OF_PRODUCT_PRODUCED, numberService.setScaleWithDefaultMathContext(doneQuantity));
        }
    }

    public void onCorrectingTheRequestedVolume(final DataDefinition orderDD, final Entity order) {
        if (!neededWhenCorrectingTheRequestedVolume()) {
            return;
        }

        Long orderId = order.getId();

        if (orderId == null) {
            return;
        }

        String state = order.getStringField(OrderFields.STATE);

        if (OrderState.ACCEPTED.getStringValue().equals(state) || OrderState.IN_PROGRESS.getStringValue().equals(state)
                || OrderState.INTERRUPTED.getStringValue().equals(state)) {

            Entity orderFromDB = orderService.getOrder(orderId);

            BigDecimal commissionedCorrectedQuantity = order.getDecimalField(OrderFields.COMMISSIONED_CORRECTED_QUANTITY);
            BigDecimal commissionedCorrectedQuantityFromDB = orderFromDB
                    .getDecimalField(OrderFields.COMMISSIONED_CORRECTED_QUANTITY);

            if ((BigDecimalUtils.convertNullToZero(commissionedCorrectedQuantity).compareTo(
                    BigDecimalUtils.convertNullToZero(commissionedCorrectedQuantityFromDB)) != 0)
                    && order.getHasManyField(OrderFields.TYPE_OF_CORRECTION_CAUSES).isEmpty()) {
                order.addGlobalError("orders.order.correctingQuantity.missingTypeOfCorrectionCauses");
            }
        }
    }

    public boolean neededWhenCorrectingTheRequestedVolume() {
        return parameterService.getParameter().getBooleanField(
                ParameterFieldsO.REASON_NEEDED_WHEN_CORRECTING_THE_REQUESTED_VOLUME);
    }

    public void setCommissionedPlannedQuantity(final DataDefinition orderDD, final Entity order) {
        if (order == null) {
            return;
        }
        Object quantity = order.getField(OrderFields.PLANNED_QUANTITY);
        if (quantity != null) {
            if (BigDecimalUtils.tryParse(quantity.toString(), LocaleContextHolder.getLocale()).isRight()) {
                order.setField(OrderFields.COMMISSIONED_PLANNED_QUANTITY,
                        numberService.setScaleWithDefaultMathContext(order.getDecimalField(OrderFields.PLANNED_QUANTITY)));
            }
        }
    }

    public void setProductQuantity(final DataDefinition orderDD, final Entity order) {
        if (order == null) {
            return;
        }

        order.setField(OrderFields.COMMISSIONED_PLANNED_QUANTITY, order.getDecimalField(OrderFields.PLANNED_QUANTITY));
        order.setField(OrderFields.COMMISSIONED_CORRECTED_QUANTITY, null);
        order.setField(OrderFields.AMOUNT_OF_PRODUCT_PRODUCED, null);
        order.setField(OrderFields.REMAINING_AMOUNT_OF_PRODUCT_TO_PRODUCE, null);
    }

    public void clearOrSetSpecyfiedValueOrderFieldsOnCopy(final DataDefinition orderDD, final Entity order) {
        order.setField(OrderFields.STATE, OrderState.PENDING.getStringValue());
        order.setField(OrderFields.EFFECTIVE_DATE_TO, null);
        order.setField(OrderFields.EFFECTIVE_DATE_FROM, null);
        order.setField(OrderFields.CORRECTED_DATE_FROM, null);
        order.setField(OrderFields.CORRECTED_DATE_TO, null);
        order.setField(OrderFields.DATE_FROM, order.getDateField(OrderFields.START_DATE));
        order.setField(OrderFields.DATE_TO, order.getDateField(OrderFields.FINISH_DATE));
        order.setField(OrderFields.DONE_QUANTITY, null);
        order.setField(OrderFields.WASTES_QUANTITY, null);
        order.setField(OrderFields.EXTERNAL_NUMBER, null);
        order.setField(OrderFields.EXTERNAL_SYNCHRONIZED, true);
        order.setField(OrderFields.COMMENT_REASON_TYPE_CORRECTION_DATE_FROM, null);
        order.setField(OrderFields.COMMENT_REASON_TYPE_CORRECTION_DATE_TO, null);
        order.setField(OrderFields.COMMENT_REASON_DEVIATION_EFFECTIVE_END, null);
        order.setField(OrderFields.COMMENT_REASON_DEVIATION_EFFECTIVE_START, null);
        order.setField(OrderFields.COMMENT_REASON_TYPE_DEVIATIONS_QUANTITY, null);
    }

    void setCopyOfTechnology(final Entity order) {
        if (orderService.isPktEnabled()) {
            order.setField(OrderFields.TECHNOLOGY, copyTechnology(order).orNull());
        } else {
            Entity prototypeTechnology = order.getBelongsToField(OrderFields.TECHNOLOGY_PROTOTYPE);
            if (prototypeTechnology != null && TechnologyState.of(prototypeTechnology).compareTo(TechnologyState.ACCEPTED) == 0) {
                order.setField(OrderFields.TECHNOLOGY, prototypeTechnology);
            } else {
                order.setField(OrderFields.TECHNOLOGY, null);
                order.setField(OrderFields.TECHNOLOGY_PROTOTYPE, null);
            }
        }
    }

    private Optional<Entity> copyTechnology(final Entity order) {
        Entity technology = order.getBelongsToField(OrderFields.TECHNOLOGY);
        if (technology == null) {
            return Optional.absent();
        }
        String number = generateTechnologyNumberFor(order).orNull();
        Entity copyOfTechnology = technology.getDataDefinition().copy(technology.getId()).get(0);
        copyOfTechnology.setField(TechnologyFields.NUMBER, number);
        copyOfTechnology.setField(TechnologyFields.TECHNOLOGY_TYPE, TechnologyType.WITH_PATTERN_TECHNOLOGY.getStringValue());
        Entity prototype = technology.getBelongsToField(TechnologyFields.TECHNOLOGY_PROTOTYPE);
        if (prototype == null) {
            copyOfTechnology.setField(TechnologyFields.TECHNOLOGY_PROTOTYPE, technology);
        } else {
            copyOfTechnology.setField(TechnologyFields.TECHNOLOGY_PROTOTYPE, prototype);
        }
        copyOfTechnology = copyOfTechnology.getDataDefinition().save(copyOfTechnology);
        if (OrderType.of(order) == OrderType.WITH_PATTERN_TECHNOLOGY) {
            technologyServiceO.changeTechnologyStateToChecked(copyOfTechnology);
        }
        return Optional.of(copyOfTechnology);
    }

    private Optional<String> generateTechnologyNumberFor(final Entity order) {
        OrderType orderType = OrderType.of(order);
        Optional<Entity> maybeTechnologyPrototype = Optional.absent();
        if (OrderType.WITH_PATTERN_TECHNOLOGY == orderType) {
            maybeTechnologyPrototype = Optional.fromNullable(order.getBelongsToField(OrderFields.TECHNOLOGY_PROTOTYPE));
        }
        return Optional.fromNullable(technologyServiceO.generateNumberForTechnologyInOrder(order,
                maybeTechnologyPrototype.orNull()));
    }

}

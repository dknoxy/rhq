/*
 * RHQ Management Platform
 * Copyright (C) 2005-2012 Red Hat, Inc.
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package org.rhq.enterprise.gui.coregui.client.admin.topology;

import static org.rhq.enterprise.gui.coregui.client.admin.topology.PartitionEventDatasourceField.FIELD_CTIME;
import static org.rhq.enterprise.gui.coregui.client.admin.topology.PartitionEventDatasourceField.FIELD_EVENT_DETAIL;
import static org.rhq.enterprise.gui.coregui.client.admin.topology.PartitionEventDatasourceField.FIELD_EVENT_TYPE;
import static org.rhq.enterprise.gui.coregui.client.admin.topology.PartitionEventDatasourceField.FIELD_EXECUTION_STATUS;
import static org.rhq.enterprise.gui.coregui.client.admin.topology.PartitionEventDatasourceField.FIELD_ID;
import static org.rhq.enterprise.gui.coregui.client.admin.topology.PartitionEventDatasourceField.FIELD_SUBJECT_NAME;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gwt.user.client.rpc.AsyncCallback;
import com.smartgwt.client.data.DSRequest;
import com.smartgwt.client.data.DSResponse;
import com.smartgwt.client.data.DataSourceField;
import com.smartgwt.client.data.Record;
import com.smartgwt.client.data.fields.DataSourceIntegerField;
import com.smartgwt.client.widgets.grid.ListGridField;
import com.smartgwt.client.widgets.grid.ListGridRecord;

import org.rhq.core.domain.cloud.PartitionEvent;
import org.rhq.core.domain.cloud.PartitionEvent.ExecutionStatus;
import org.rhq.core.domain.cloud.PartitionEventType;
import org.rhq.core.domain.criteria.PartitionEventCriteria;
import org.rhq.core.domain.util.PageControl;
import org.rhq.core.domain.util.PageList;
import org.rhq.core.domain.util.PageOrdering;
import org.rhq.enterprise.gui.coregui.client.components.table.TimestampCellFormatter;
import org.rhq.enterprise.gui.coregui.client.gwt.GWTServiceLookup;
import org.rhq.enterprise.gui.coregui.client.util.RPCDataSource;

/**
 * @author Jirka Kremser
 *
 */
public class PartitionEventDatasource extends RPCDataSource<PartitionEvent, PartitionEventCriteria> {

    // filters
    public static final String FILTER_EVENT_DETAIL = "eventDetail";
    public static final String FILTER_EXECUTION_STATUS = "executionStatus";
    public static final String FILTER_EVENT_TYPE = "eventType";

    public PartitionEventDatasource() {
        super();
        List<DataSourceField> fields = addDataSourceFields();
        addFields(fields);
    }

    @Override
    protected List<DataSourceField> addDataSourceFields() {
        List<DataSourceField> fields = super.addDataSourceFields();
        DataSourceField idField = new DataSourceIntegerField(FIELD_ID.propertyName(), FIELD_ID.title(), 50);
        idField.setPrimaryKey(true);
        idField.setHidden(true);
        fields.add(idField);
        return fields;
    }

    public List<ListGridField> getListGridFields() {
        List<ListGridField> fields = new ArrayList<ListGridField>();

        ListGridField idField = FIELD_ID.getListGridField();
        idField.setHidden(true);
        fields.add(idField);
        ListGridField executionTimeField = FIELD_CTIME.getListGridField("125");
        TimestampCellFormatter.prepareDateField(executionTimeField);
        fields.add(executionTimeField);
        fields.add(FIELD_EVENT_TYPE.getListGridField("215"));
        fields.add(FIELD_EVENT_DETAIL.getListGridField("*"));
        fields.add(FIELD_SUBJECT_NAME.getListGridField("100"));
        fields.add(FIELD_EXECUTION_STATUS.getListGridField("100"));

        return fields;
    }

    @Override
    protected void executeFetch(final DSRequest request, final DSResponse response, PartitionEventCriteria criteria) {
        //        final PageControl pc = getPageControl(request);

        GWTServiceLookup.getCloudService().findPartitionEventsByCriteria(criteria,
            new AsyncCallback<PageList<PartitionEvent>>() {
                public void onSuccess(PageList<PartitionEvent> result) {
                    response.setData(buildRecords(result));
                    response.setTotalRows(result.size());
                    processResponse(request.getRequestId(), response);
                }

                @Override
                public void onFailure(Throwable t) {
                    //todo: CoreGUI.getErrorHandler().handleError(MSG.view_admin_plugins_loadFailure(), t);
                    response.setStatus(DSResponse.STATUS_FAILURE);
                    processResponse(request.getRequestId(), response);
                }
            });
    }

    /**
     * Returns a prepopulated PageControl based on the provided DSRequest. This will set sort fields,
     * pagination, but *not* filter fields.
     *
     * @param request the request to turn into a page control
     * @return the page control for passing to criteria and other queries
     */
    protected PageControl getPageControl(DSRequest request) {
        // Initialize paging.         
        PageControl pageControl = new PageControl(0, getDataPageSize());

        // Initialize sorting.
        String sortBy = request.getAttribute("sortBy");
        if (sortBy != null) {
            String[] sorts = sortBy.split(",");
            for (String sort : sorts) {
                PageOrdering ordering = (sort.startsWith("-")) ? PageOrdering.DESC : PageOrdering.ASC;
                String columnName = (ordering == PageOrdering.DESC) ? sort.substring(1) : sort;
                pageControl.addDefaultOrderingField(columnName, ordering);
            }
        }

        return pageControl;
    }

    @Override
    public PartitionEvent copyValues(Record from) {
        throw new UnsupportedOperationException("PartitionEventDatasource.copyValues(Record from)");
    }

    @Override
    public ListGridRecord copyValues(PartitionEvent from) {
        ListGridRecord record = new ListGridRecord();
        record.setAttribute(FIELD_ID.propertyName(), from.getId());
        record.setAttribute(FIELD_CTIME.propertyName(), from.getCtime());
        record.setAttribute(FIELD_EVENT_TYPE.propertyName(), from.getEventType() == null ? "" : from.getEventType());
        record.setAttribute(FIELD_EVENT_DETAIL.propertyName(), from.getEventDetail() == null ? "" : from.getEventDetail());
        record.setAttribute(FIELD_SUBJECT_NAME.propertyName(), from.getSubjectName() == null ? "" : from.getSubjectName());
        record.setAttribute(FIELD_EXECUTION_STATUS.propertyName(), from.getExecutionStatus() == null ? "" : from.getExecutionStatus());
        return record;
    }

    @Override
    protected PartitionEventCriteria getFetchCriteria(DSRequest request) {
        //todo: do it like in ResourceDatasource.class
        
        
        ExecutionStatus[] statuses = getArrayFilter(request, FILTER_EXECUTION_STATUS, ExecutionStatus.class);
        PartitionEventType[] types = getArrayFilter(request, FILTER_EVENT_TYPE, PartitionEventType.class);
        if (types == null || types.length == 0 || statuses == null || statuses.length == 0) {
            return null; // user didn't select any type or status - return null to indicate no data should be displayed
        }

        PartitionEventCriteria criteria = new PartitionEventCriteria();
        // This code is unlikely to be necessary as the encompassing view should be using an initial
        // sort specifier. But just in case, make sure we set the initial sort.  Note that we have to
        // manipulate the PageControl directly as per the restrictions on getFetchCriteria() (see jdoc).
        PageControl pageControl = getPageControl(request);
        if (pageControl.getOrderingFields().isEmpty()) {
            pageControl.initDefaultOrderingField(FIELD_CTIME.propertyName(), PageOrdering.DESC);
        }

        // TODO: This call is broken in 2.2, http://code.google.com/p/smartgwt/issues/detail?id=490
        // when using AdvancedCriteria
        Map<String, Object> criteriaMap = request.getCriteria().getValues();
        criteria.addFilterEventDetail((String) criteriaMap.get(FILTER_EVENT_DETAIL));

        // There's no need to add a exec. status filter to the criteria if the user specified all exec. statuses.
        if (statuses.length != ExecutionStatus.values().length) {
            criteria.addFilterExecutionStatus(statuses);
        }
        
        // There's no need to add a event type filter to the criteria if the user specified all event types.
        if (types.length != PartitionEventType.values().length) {
            criteria.addFilterEventType(types);
        }

        return criteria;
    }
}
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
package org.rhq.enterprise.gui.coregui.client.admin.storage;

import static org.rhq.enterprise.gui.coregui.client.admin.storage.StorageNodeDatasourceField.FIELD_ADDRESS;
import static org.rhq.enterprise.gui.coregui.client.admin.storage.StorageNodeDatasourceField.FIELD_RESOURCE_ID;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.google.gwt.user.client.rpc.AsyncCallback;
import com.smartgwt.client.data.Criteria;
import com.smartgwt.client.types.Alignment;
import com.smartgwt.client.types.ListGridEditEvent;
import com.smartgwt.client.types.RowEndEditAction;
import com.smartgwt.client.types.SortDirection;
import com.smartgwt.client.util.BooleanCallback;
import com.smartgwt.client.util.SC;
import com.smartgwt.client.widgets.Canvas;
import com.smartgwt.client.widgets.IButton;
import com.smartgwt.client.widgets.events.ClickEvent;
import com.smartgwt.client.widgets.events.ClickHandler;
import com.smartgwt.client.widgets.grid.CellFormatter;
import com.smartgwt.client.widgets.grid.ListGrid;
import com.smartgwt.client.widgets.grid.ListGridField;
import com.smartgwt.client.widgets.grid.ListGridRecord;
import com.smartgwt.client.widgets.layout.HLayout;
import com.smartgwt.client.widgets.toolbar.ToolStrip;

import org.rhq.core.domain.authz.Permission;
import org.rhq.core.domain.cloud.StorageNode.OperationMode;
import org.rhq.enterprise.gui.coregui.client.CoreGUI;
import org.rhq.enterprise.gui.coregui.client.IconEnum;
import org.rhq.enterprise.gui.coregui.client.LinkManager;
import org.rhq.enterprise.gui.coregui.client.admin.AdministrationView;
import org.rhq.enterprise.gui.coregui.client.components.table.AuthorizedTableAction;
import org.rhq.enterprise.gui.coregui.client.components.table.TableActionEnablement;
import org.rhq.enterprise.gui.coregui.client.components.table.TableSection;
import org.rhq.enterprise.gui.coregui.client.components.view.HasViewName;
import org.rhq.enterprise.gui.coregui.client.components.view.ViewName;
import org.rhq.enterprise.gui.coregui.client.gwt.GWTServiceLookup;
import org.rhq.enterprise.gui.coregui.client.util.StringUtility;
import org.rhq.enterprise.gui.coregui.client.util.async.Command;
import org.rhq.enterprise.gui.coregui.client.util.async.CountDownLatch;
import org.rhq.enterprise.gui.coregui.client.util.enhanced.EnhancedVLayout;
import org.rhq.enterprise.gui.coregui.client.util.message.Message;

/**
 * Shows the table of all storage nodes.
 *
 * @author Jirka Kremser
 */
public class StorageNodeTableView extends
    TableSection<StorageNodeDatasource> implements HasViewName {

    public static final ViewName VIEW_ID = new ViewName("StorageNodes", MSG.view_adminTopology_storageNodes(), IconEnum.STORAGE_NODE);

    public static final String VIEW_PATH = AdministrationView.VIEW_ID + "/"
        + AdministrationView.SECTION_TOPOLOGY_VIEW_ID + "/" + VIEW_ID;

    public StorageNodeTableView() {
        super(null);
        setHeight100();
        setWidth100();
        Criteria criteria = new Criteria();
        String[] modes = new String[OperationMode.values().length];
        int i = 0;
        for (OperationMode value : OperationMode.values()) {
            modes[i++] = value.name();
        }
        criteria.addCriteria(StorageNodeDatasource.FILTER_OPERATION_MODE, modes);
        setInitialCriteria(criteria);
        setDataSource(new StorageNodeDatasource());
    }

    @Override
    protected void configureTable() {
        super.configureTable();
        List<ListGridField> fields = getDataSource().getListGridFields();
        ListGrid listGrid = getListGrid();
        listGrid.setFields(fields.toArray(new ListGridField[fields.size()]));
        listGrid.sort(FIELD_ADDRESS.propertyName(), SortDirection.ASCENDING);
        showCommonActions();

        for (ListGridField field : fields) {
            // adding the cell formatter for name field (clickable link)
            if (field.getName() == FIELD_ADDRESS.propertyName()) {
                field.setCellFormatter(new CellFormatter() {
                    @Override
                    public String format(Object value, ListGridRecord record, int rowNum, int colNum) {
                        if (value == null) {
                            return "";
                        }
                        String detailsUrl = "#" + VIEW_PATH + "/" + getId(record);
                        String formattedValue = StringUtility.escapeHtml(value.toString());
                        return LinkManager.getHref(detailsUrl, formattedValue);

                    }
                });
            } else if (field.getName() == FIELD_RESOURCE_ID.propertyName()) {
                // adding the cell formatter for resource id field (clickable link)
                field.setCellFormatter(new CellFormatter() {
                    @Override
                    public String format(Object value, ListGridRecord record, int rowNum, int colNum) {
                        if (value == null || value.toString().isEmpty()) {
                            return "";
                        }
                        String rawUrl = null;
                        try {
                            rawUrl = LinkManager.getResourceLink(record.getAttributeAsInt(FIELD_RESOURCE_ID.propertyName()));
                        } catch (NumberFormatException nfe) {
                            rawUrl = MSG.common_label_none();
                        }
                        
                        String formattedValue = StringUtility.escapeHtml(rawUrl);
                        String label = StringUtility.escapeHtml("Link to Resource");
                        return LinkManager.getHref(formattedValue, label);
                    }
                });
            }
        }
    }
    
    @Override
    protected ListGrid createListGrid() {
        ListGrid listGrid = new ListGrid() {
            @Override
            protected Canvas getExpansionComponent(final ListGridRecord record) {
                final ListGrid grid = this;  
                
                EnhancedVLayout layout = new EnhancedVLayout(5);  
                layout.setPadding(5);  
  
                final ListGrid countryGrid = new ListGrid();  
                countryGrid.setWidth100();  
                countryGrid.setHeight(224);  
//                countryGrid.setCellHeight(22);  
//                countryGrid.setDataSource(getRelatedDataSource(record));  
//                countryGrid.fetchRelatedData(record, SupplyCategoryXmlDS.getInstance());  
  
//                countryGrid.setCanEdit(true);  
//                countryGrid.setModalEditing(true);  
//                countryGrid.setEditEvent(ListGridEditEvent.CLICK);
//                countryGrid.setListEndEditAction(RowEndEditAction.NEXT);
//                countryGrid.setAutoSaveEdits(false);
                ListGridField fooField = new ListGridField("foo", "Heap Used");
                fooField.setWidth("*");
                countryGrid.setFields(fooField, new ListGridField("bar", "Heap Max",
                    100), new ListGridField("baz", "Heap Percentage", 100), new ListGridField("fooBar", "Tokens", 100),
                    new ListGridField("fooBaz", "Ownage", 100));
                ListGridRecord[] data = new ListGridRecord[5];
                for (int i = 0; i < data.length; i++) {
                    ListGridRecord fooRecord = new ListGridRecord();
                    fooRecord.setAttribute("foo", new Random().nextInt(100));
                    fooRecord.setAttribute("bar", new Random().nextInt(100));
                    fooRecord.setAttribute("baz", new Random().nextInt(100));
                    fooRecord.setAttribute("fooBar", new Random().nextInt(100));
                    fooRecord.setAttribute("fooBaz", new Random().nextInt(100));
                    data[i] = fooRecord;
                }
                countryGrid.setData(data);
  
                layout.addMember(countryGrid);  
//  
//                HLayout hLayout = new HLayout(10);  
//                hLayout.setAlign(Alignment.CENTER);  
//  
                IButton saveButton = new IButton("Save");  
                saveButton.setTop(250);  
                saveButton.addClickHandler(new ClickHandler() {  
                    public void onClick(ClickEvent event) {  
                        countryGrid.saveAllEdits();  
                    }  
                });  
//                hLayout.addMember(saveButton);  
//  
//                IButton discardButton = new IButton("Discard");  
//                discardButton.addClickHandler(new ClickHandler() {  
//                    public void onClick(ClickEvent event) {  
//                        countryGrid.discardAllEdits();  
//                    }  
//                });  
//                hLayout.addMember(discardButton);  
//  
                IButton closeButton = new IButton("Close");  
                closeButton.addClickHandler(new ClickHandler() {  
                    public void onClick(ClickEvent event) {  
                        grid.collapseRecord(record);  
                    }  
                });  
//                hLayout.addMember(closeButton);  
//                                                 
//                layout.addMember(hLayout);
                ToolStrip toolStrip = new ToolStrip();
                toolStrip.addMember(saveButton);
                toolStrip.addMember(closeButton);
                layout.addMember(toolStrip);
                layout.setBackgroundColor("#FFFFFF");
                return layout;  
            }
        };
        listGrid.setCanExpandRecords(true);
//        listGrid.setBaseStyle("storageNodeGridCell");
//        listGrid.setDetailDS(detailDS)
        return listGrid;
    }

    @Override
    public Canvas getDetailsView(Integer id) {
        return new StorageNodeDetailView(id);
    }

    private void showCommonActions() {
        addInvokeOperationsAction();

//        addTableAction(MSG.view_adminTopology_server_removeSelected(), null, new AuthorizedTableAction(this,
//            TableActionEnablement.ANY, Permission.MANAGE_SETTINGS) {
//            public void executeAction(final ListGridRecord[] selections, Object actionValue) {
//                final List<String> selectedAddresses = getSelectedAddresses(selections);
//                String message = MSG.view_adminTopology_message_removeServerConfirm(selectedAddresses.toString());
//                SC.ask(message, new BooleanCallback() {
//                    public void execute(Boolean confirmed) {
//                        if (confirmed) {
//                            SC.say("You've selected:\n\n" + selectedAddresses);
////                            int[] selectedIds = getSelectedIds(selections);
////                            GWTServiceLookup.getTopologyService().deleteServers(selectedIds, new AsyncCallback<Void>() {
////                                public void onSuccess(Void arg0) {
////                                    Message msg = new Message(MSG.view_adminTopology_message_removedServer(String
////                                        .valueOf(selections.length)), Message.Severity.Info);
////                                    CoreGUI.getMessageCenter().notify(msg);
////                                    refresh();
////                                }
////
////                                public void onFailure(Throwable caught) {
////                                    CoreGUI.getErrorHandler().handleError(
////                                        MSG.view_adminTopology_message_removeServerFail(String
////                                            .valueOf(selections.length)) + " " + caught.getMessage(), caught);
////                                    refreshTableInfo();
////                                }
////
////                            });
//                        }
//                    }
//                });
//            }
//        });
    }

    private void addInvokeOperationsAction() {
        Map<String, Object> operationsMap = new LinkedHashMap<String, Object>();
        operationsMap.put("Start", "start");
        operationsMap.put("Shutdown", "shutdown");
        operationsMap.put("Restart", "restart");
        operationsMap.put("Disable Debug Mode", "stopRPCServer");
        operationsMap.put("Enable Debug Mode", "startRPCServer");
//        operationsMap.put("Decommission", "decommission");

        addTableAction(MSG.common_title_operation(), null, operationsMap, new AuthorizedTableAction(this,
            TableActionEnablement.ANY, Permission.MANAGE_SETTINGS) {

            @Override
            public boolean isEnabled(ListGridRecord[] selection) {
                return StorageNodeTableView.this.isEnabled(super.isEnabled(selection), selection);
            };
            
            @Override
            public void executeAction(final ListGridRecord[] selections, Object actionValue) {
                final String operationName = (String) actionValue;
                final List<String> selectedAddresses = getSelectedAddresses(selections);
//                String message = MSG.view_adminTopology_message_setModeConfirm(selectedAddresses.toString(), mode.name());
                SC.ask("Are you sure, you want to run operation " + operationName + "?" , new BooleanCallback() {
                    public void execute(Boolean confirmed) {
                        if (confirmed) {
                            final CountDownLatch latch = CountDownLatch.create(selections.length, new Command() {
                                @Override
                                public void execute() {
//                                    Message msg = new Message(MSG.view_adminTopology_message_setMode(
                                    //                                      String.valueOf(selections.length), mode.name()), Message.Severity.Info);
                                    Message msg = new Message("Operation" + operationName
                                        + " was successfully scheduled for resources with ids"
                                        + Arrays.asList(getSelectedIds(selections)), Message.Severity.Info);
                                    CoreGUI.getMessageCenter().notify(msg);
                                    refreshTableInfo();
                                }
                            });
                            boolean isStopStartOrRestart = Arrays.asList("start", "shutdown", "restart").contains(
                                operationName);
                            for (ListGridRecord storageNodeRecord : selections) {
                                // NFE should never happen, because of the condition for table action enablement
                                int resourceId = storageNodeRecord.getAttributeAsInt(FIELD_RESOURCE_ID.propertyName());
                                if (isStopStartOrRestart) {
                                    // start, stop or restart the storage node
                                    GWTServiceLookup.getOperationService().scheduleResourceOperation(resourceId,
                                        operationName, null, "Run by Storage Node Administrations UI", 0,
                                        new AsyncCallback<Void>() {
                                            public void onSuccess(Void result) {
                                                latch.countDown();
                                            }
                                            public void onFailure(Throwable caught) {
                                                CoreGUI.getErrorHandler().handleError(
                                                    "Scheduling operation " + operationName
                                                        + " failed for resources with ids"
                                                        + Arrays.asList(getSelectedIds(selections)) + " "
                                                        + caught.getMessage(), caught);
                                                latch.countDown();
                                                refreshTableInfo();
                                            }
                                        });
                                } else {
                                    // invoke the operation on the storage service resource
                                    GWTServiceLookup.getStorageService().invokeOperationOnStorageService(resourceId,
                                        operationName, new AsyncCallback<Void>() {
                                            public void onSuccess(Void result) {
                                                latch.countDown();
                                            }

                                            public void onFailure(Throwable caught) {
                                                CoreGUI.getErrorHandler().handleError(
                                                    "Scheduling operation " + operationName
                                                        + " failed for resources with ids"
                                                        + Arrays.asList(getSelectedIds(selections)) + " "
                                                        + caught.getMessage(), caught);
                                                latch.countDown();
                                                refreshTableInfo();
                                            }
                                        });
                                }
                            }
                            
                            
//                            int[] selectedIds = getSelectedIds(selections);
//                            GWTServiceLookup.getTopologyService().updateServerMode(selectedIds, mode,
//                                new AsyncCallback<Void>() {
//                                    public void onSuccess(Void result) {
//                                        Message msg = new Message(MSG.view_adminTopology_message_setMode(
//                                            String.valueOf(selections.length), mode.name()), Message.Severity.Info);
//                                        CoreGUI.getMessageCenter().notify(msg);
//                                        refresh();
//                                    }
//
//                                    public void onFailure(Throwable caught) {
//                                        CoreGUI.getErrorHandler().handleError(
//                                            MSG.view_adminTopology_message_setModeFail(
//                                                String.valueOf(selections.length), mode.name())
//                                                + " " + caught.getMessage(), caught);
//                                        refreshTableInfo();
//                                    }
//
//                                });
                        } else {
                            refreshTableInfo();
                        }
                    }
                });
            }
        });
    }

    private int[] getSelectedIds(ListGridRecord[] selections) {
        if (selections == null) {
            return new int[0];
        }
        int[] ids = new int[selections.length];
        int i = 0;
        for (ListGridRecord selection : selections) {
            ids[i++] = selection.getAttributeAsInt(FIELD_ID);
        }
        return ids;
    }

    private List<String> getSelectedAddresses(ListGridRecord[] selections) {
        if (selections == null) {
            return new ArrayList<String>(0);
        }
        List<String> ids = new ArrayList<String>(selections.length);
        for (ListGridRecord selection : selections) {
            ids.add(selection.getAttributeAsString(FIELD_ADDRESS.propertyName()));
        }
        return ids;
    }
    
    private boolean isEnabled(boolean parentsOpinion, ListGridRecord[] selection) {
        if (!parentsOpinion) {
            return false;
        }
        for (ListGridRecord storageNodeRecord : selection) {
            if (storageNodeRecord.getAttribute(FIELD_RESOURCE_ID.propertyName()) == null) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ViewName getViewName() {
        return VIEW_ID;
    }

    @Override
    protected String getBasePath() {
        return VIEW_PATH;
    }
}

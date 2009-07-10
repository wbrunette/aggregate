/*
 * Copyright (C) 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.odk.aggregate.parser;

import com.google.appengine.api.datastore.Key;

import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.instance.DataModelTree;
import org.javarosa.core.model.instance.TreeElement;
import org.javarosa.xform.util.XFormUtils;

import org.odk.aggregate.PMFactory;
import org.odk.aggregate.constants.ParserConsts;
import org.odk.aggregate.exception.ODKFormAlreadyExistsException;
import org.odk.aggregate.exception.ODKFormNotFoundException;
import org.odk.aggregate.form.Form;
import org.odk.aggregate.form.FormElement;
import org.odk.aggregate.submission.SubmissionFieldType;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

import javax.jdo.PersistenceManager;

/**
 * Parses an XML definition of an XForm based on java rosa types
 *
 * @author wbrunette@gmail.com
 *
 */
public class FormParserForJavaRosa {

  /**
   * The ODK Id that uniquely identifies the form
   */
  private String odkId = ParserConsts.DEFAULT_NAMESPACE;

  /**
   * The XForm definition in XML
   */
  private String xml;

  /**
   * Locale Data
   */
  private String[] localeData;

  /**
   * The XForm data mapping for ODK aggregate
   */
  private Form form;

  /**
   * The form element nodes to be persisted
   */
  // TODO: remove the need for createdNodes
  private List<FormElement> createdNodes = new ArrayList<FormElement>();
  
  private PersistenceManager pm;

  /**
   * Constructor that parses and xform from the input stream supplied and
   * creates the proper ODK Aggregate Form definition in the gae datastore
   * 
   * @param formName
   *    name of xform to be parsed
   *    
   * @param userName
   *    name of user who uploaded the form
   * 
   * @param inputXml
   *    input stream containing the Xform definition
   *  
   * @param fileName
   *    file name used for a file that specifies the form's XML definition
   * 
   * @throws ODKFormAlreadyExistsException
   */
  public FormParserForJavaRosa(String formName, String userName, String inputXml, String fileName) throws ODKFormAlreadyExistsException {

    xml = inputXml;    
    String strippedXML = JRHelperUtil.removeNonJavaRosaCompliantTags(xml);
    FormDef formDef = XFormUtils.getFormFromInputStream(new ByteArrayInputStream(strippedXML.getBytes()));

    // TODO: figure out a better way to handle this situation
    if (formDef == null) {
      return;
    }

    DataModelTree dataModel = formDef.getDataModel();
    TreeElement rootElement = dataModel.getRoot();
    if (rootElement != null) {
      for (int i = 0; i < rootElement.getAttributeCount(); i++) {
        String name = rootElement.getAttributeName(i);
        if (name.equals(ParserConsts.ODK_ATTRIBUTE_NAME)) {
          odkId = rootElement.getAttributeValue(i);
          break;
        }
      }
    }

    if (odkId.equals(ParserConsts.DEFAULT_NAMESPACE) && dataModel.schema != null) {
      odkId = dataModel.schema;
    }

    pm = PMFactory.get().getPersistenceManager();
    
    try {
      @SuppressWarnings("unused")
      Form preexistingForm = Form.retrieveForm(pm, odkId);
      throw new ODKFormAlreadyExistsException();
    } catch (ODKFormNotFoundException e1) {
      // should throw an exception, else form already exists and exit parse
    }

    // get local Ids
    if (formDef.getLocalizer() != null) {
      localeData = formDef.getLocalizer().getAvailableLocales();
    }
    
    // TODO: clean up data access & recursion - a bit sloppy
    form = new Form(odkId, formName, userName, xml, fileName);
    FormElement root = processTreeElements(dataModel.getRoot(), form.getKey(), null);

    // TODO: get rid of persist hack once bug is resolved that allows JDO to
    // have a tree
    // TODO: or try two way pointers
    // TODO: better error handling

    for (FormElement node : createdNodes) {
      try {
        pm.makePersistent(node);
      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    pm.close();

    form.setElementTreeRoot(root);
  }

  /**
   * Get form definition
   * 
   * @return
   *    form definition 
   */
  public Form getForm() {
    return form;
  }

  /**
   * Used to recursively process the xform definition tree to create the 
   * form element nodes to define the form type mapping to the odk aggregate
   * conversion types  
   * 
   * @param treeElement
   *    java rosa tree element
   * 
   * @param parentKey
   *    key from the parent form for proper entity group usage in gae
   * 
   * @param parent
   *    parent form element   
   * 
   * @return
   *    form element containing the needed info from the xform definition 
   * 
   */
  private FormElement processTreeElements(TreeElement treeElement, Key parentKey, FormElement parent) {
    System.out.println("processing te: " + treeElement.getName() + " type: " + treeElement.dataType
        + " repeatable: " + treeElement.repeatable);
    
    FormElement dataElement =
        new FormElement(parentKey, treeElement.getName(), odkId, SubmissionFieldType
            .convertJavaRosaType(treeElement.dataType), treeElement.repeatable);

    createdNodes.add(dataElement);

    if (parent != null) {
      parent.addChild(dataElement);
    }

    if(dataElement.isRepeatable()) {
      form.addRepeat(dataElement, pm);
    }
    
    @SuppressWarnings("unchecked")
    Vector<TreeElement> children = treeElement.getChildren();
    if (children == null) {
      return dataElement;
    }
    for (TreeElement element : children) {
      processTreeElements(element, dataElement.getKey(), dataElement);
    }
    return dataElement;
  }

}
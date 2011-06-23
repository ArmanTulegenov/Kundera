/*
 * Copyright 2011 Impetus Infotech.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.impetus.kundera.pelops;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.UUID;

import javax.persistence.PersistenceException;

import org.apache.cassandra.thrift.Column;
import org.apache.cassandra.thrift.SuperColumn;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.impetus.kundera.Constants;
import com.impetus.kundera.client.PelopsClient;
import com.impetus.kundera.client.PelopsClient.ThriftRow;
import com.impetus.kundera.ejb.EntityManagerImpl;
import com.impetus.kundera.metadata.EntityMetadata;
import com.impetus.kundera.property.PropertyAccessException;
import com.impetus.kundera.property.PropertyAccessorFactory;
import com.impetus.kundera.property.PropertyAccessorHelper;
import com.impetus.kundera.proxy.EnhancedEntity;
import com.impetus.kundera.utils.ReflectUtils;

/**
 * Provides Pelops utility methods for data held in Column family based stores   
 * @author amresh.singh
 */
public class PelopsDataHandler {
	private static Log log = LogFactory.getLog(PelopsDataHandler.class);
	
	/** The Constant TO_ONE_SUPER_COL_NAME. */
    private static final String TO_ONE_SUPER_COL_NAME = "FKey-TO";
	
	/**
     * From thrift row.
     *
     * @param <E> the element type
     * @param em the em
     * @param clazz the clazz
     * @param m the m
     * @param cr the cr
     * @return the e
     * @throws Exception the exception
     */
    public  <E> E fromColumnThriftRow(EntityManagerImpl em, Class<E> clazz, EntityMetadata m, PelopsClient.ThriftRow cr)
            throws Exception {

        // Instantiate a new instance
        E e = clazz.newInstance();

        // Set row-key. Note: @Id is always String.
        PropertyAccessorHelper.set(e, m.getIdProperty(), cr.getId());

        // Iterate through each column
        for (Column c : cr.getColumns())
        {
            String name = PropertyAccessorFactory.STRING.fromBytes(c.getName());
            byte[] value = c.getValue();

            if (null == value)
            {
                continue;
            }

            // check if this is a property?
            EntityMetadata.Column column = m.getColumn(name);
            if (null == column)
            {
                // it could be some relational column
                EntityMetadata.Relation relation = m.getRelation(name);

                if (relation == null)
                {
                    continue;
                }

                String foreignKeys = PropertyAccessorFactory.STRING.fromBytes(value);
                Set<String> keys = deserializeKeys(foreignKeys);
                em.getEntityResolver().populateForeignEntities(e, cr.getId(), relation, keys.toArray(new String[0]));
            }

            else
            {
                try
                {
                    PropertyAccessorHelper.set(e, column.getField(), value);
                }
                catch (PropertyAccessException pae)
                {
                    log.warn(pae.getMessage());
                }
            }
        }

        return e;
    }
    
	public <E> E fromSuperColumnThriftRow(EntityManagerImpl em, Class<E> clazz, EntityMetadata m, ThriftRow tr) throws Exception {

		// Instantiate a new instance
		E e = clazz.newInstance();

		// Set row-key. Note: @Id is always String.
		PropertyAccessorHelper.set(e, m.getIdProperty(), tr.getId());

		// Get a name->field map for super-columns
		Map<String, Field> columnNameToFieldMap = new HashMap<String, Field>();
		Map<String, Field> superColumnNameToFieldMap = new HashMap<String, Field>();
		
		for (Map.Entry<String, EntityMetadata.SuperColumn> entry : m
				.getSuperColumnsMap().entrySet()) {
			EntityMetadata.SuperColumn scMetadata = entry.getValue();
			superColumnNameToFieldMap.put(scMetadata.getName(), scMetadata.getField());
			for (EntityMetadata.Column cMetadata : entry.getValue()
					.getColumns()) {
				columnNameToFieldMap.put(cMetadata.getName(),
						cMetadata.getField());
			}
		}
		
		//Add all super columns to entity
		Collection embeddedCollection = null;
		Field embeddedCollectionField = null;
		for (SuperColumn sc : tr.getSuperColumns()) {
			String scName = PropertyAccessorFactory.STRING.fromBytes(sc.getName());
			String scNamePrefix = null;			
			
			//If this super column is variable in number (name#sequence format)
			if(scName.indexOf(Constants.SUPER_COLUMN_NAME_DELIMITER) != -1) {
				StringTokenizer st = new StringTokenizer(scName, Constants.SUPER_COLUMN_NAME_DELIMITER);
				if(st.hasMoreTokens()) {
					scNamePrefix = st.nextToken();
				}
				
				embeddedCollectionField = superColumnNameToFieldMap.get(scNamePrefix);
				Class embeddedCollectionFieldClass = embeddedCollectionField.getType();				
				
				if(embeddedCollection == null || embeddedCollection.isEmpty()) {
					if(embeddedCollectionFieldClass.equals(List.class)) {
						embeddedCollection = new ArrayList<Object>();
					} else if(embeddedCollectionFieldClass.equals(Set.class)) {					
						embeddedCollection = new HashSet<Object>();
					} else {
						throw new PersistenceException("Super Column " + scName + " doesn't match with entity which should have been a Collection");
					}
				}		
				
				
				Class<?> embeddedClass = null;
				Type[] parameters = ReflectUtils.getTypeArguments(embeddedCollectionField);
				if (parameters != null) {
					if (parameters.length == 1) {
						embeddedClass = (Class<?>) parameters[0];
					} else {
						throw new PersistenceException("How many parameters man?");
					}
				}
				
				System.out.println(embeddedClass);
				// must have a default no-argument constructor
		        try {
		        	embeddedClass.getConstructor();
		        } catch (NoSuchMethodException nsme) {
		            throw new PersistenceException(embeddedClass.getName() + " is @Embeddable and must have a default no-argument constructor.");
		        }
				Object embeddedObject = embeddedClass.newInstance();
				
				
				for(Column column : sc.getColumns()) {
					String name = PropertyAccessorFactory.STRING.fromBytes(column.getName());
					byte[] value = column.getValue();
					if (value == null) {
						continue;
					}
					Field columnField = columnNameToFieldMap.get(name);
					PropertyAccessorHelper.set(embeddedObject, columnField, value);
				}
				embeddedCollection.add(embeddedObject);			
				
			} else {
				Field superColumnField = superColumnNameToFieldMap.get(scName);
				Class superColumnClass = superColumnField.getType();
				Object superColumnObj = superColumnClass.newInstance();
				
				boolean intoRelations = false;
				if (scName.equals(TO_ONE_SUPER_COL_NAME)) {
					intoRelations = true;
				}

				for (Column column : sc.getColumns()) {
					String name = PropertyAccessorFactory.STRING.fromBytes(column.getName());
					byte[] value = column.getValue();

					if (value == null) {
						continue;
					}

					if (intoRelations) {
						EntityMetadata.Relation relation = m.getRelation(name);

						String foreignKeys = PropertyAccessorFactory.STRING
								.fromBytes(value);
						Set<String> keys = deserializeKeys(foreignKeys);
						em.getEntityResolver()
								.populateForeignEntities(e, tr.getId(), relation,
										keys.toArray(new String[0]));

					} else {
						// set value of the field in the bean
						Field columnField = columnNameToFieldMap.get(name);
						
						
						PropertyAccessorHelper.set(superColumnObj, columnField, value);
					}
				}
				PropertyAccessorHelper.set(e, superColumnField, superColumnObj);			
			}
			
			
		}
		
		if(embeddedCollection != null && ! embeddedCollection.isEmpty()) {
			PropertyAccessorHelper.set(e, embeddedCollectionField, embeddedCollection);
		}
		return e;
	}

    /**
     * Helper method to convert @Entity to ThriftRow.
     *
     * @param e the e
     * @param columnsLst the columns lst
     * @param columnFamily the colmun family
     * @return the base data accessor. thrift row
     * @throws Exception the exception
     */
    public PelopsClient.ThriftRow toThriftRow(EnhancedEntity e, EntityMetadata m, String columnFamily) 
    	throws Exception {    	
    	// timestamp to use in thrift column objects
        long timestamp = System.currentTimeMillis();

        PelopsClient.ThriftRow tr = new PelopsClient(). new ThriftRow();

        tr.setColumnFamilyName(columnFamily);	        		// column-family name       
        tr.setId(e.getId());									// Id
        
        addSuperColumnsToThriftRow(timestamp, tr, m, e);		//Super columns  
        
        if(m.getSuperColumnsAsList().isEmpty()) {
        	addColumnsToThriftRow(timestamp, tr, m, e);				//Columns
        }        

        return tr;
    }
        
    public void addColumnsToThriftRow(long timestamp, PelopsClient.ThriftRow tr, EntityMetadata m, EnhancedEntity e) throws Exception {
    	List<Column> columns = new ArrayList<Column>();
    	
        // Iterate through each column-meta and populate that with field values
        for (EntityMetadata.Column column : m.getColumnsAsList()) {
            Field field = column.getField();
            String name = column.getName();
            try {
                byte[] value = PropertyAccessorHelper.get(e.getEntity(), field);
                Column col = new Column();
                col.setName(PropertyAccessorFactory.STRING.toBytes(name));
                col.setValue(value);
                col.setTimestamp(timestamp);
                columns.add(col);
            } catch (PropertyAccessException exp) {
                log.warn(exp.getMessage());
            }

        }

        // add foreign keys
        for (Map.Entry<String, Set<String>> entry : e.getForeignKeysMap().entrySet()) {
            String property = entry.getKey();
            Set<String> foreignKeys = entry.getValue();

            String keys = serializeKeys(foreignKeys);
            if (null != keys) {
                Column col = new Column();

                col.setName(PropertyAccessorFactory.STRING.toBytes(property));
                col.setValue(PropertyAccessorFactory.STRING.toBytes(keys));
                col.setTimestamp(timestamp);
                columns.add(col);
            }
        }
        tr.setColumns(columns);			//Columns
    }
    
    public void addSuperColumnsToThriftRow(long timestamp, PelopsClient.ThriftRow tr, EntityMetadata m, EnhancedEntity e) throws Exception {
    	 //Iterate through Super columns
        for (EntityMetadata.SuperColumn superColumn : m.getSuperColumnsAsList()) {            
            Field superColumnField = superColumn.getField();
            Object superColumnObject = PropertyAccessorHelper.getObject(e.getEntity(), superColumnField);
             
            
            //If Embedded object is a Collection, there will be variable number of super columns one for each object in collection.
            //Key for each super column will be of the format "<Embedded object field name>#<Unique sequence number>
            
            //On the other hand, if embedded object is not a Collection, it would simply be embedded as ONE super column.
            if(superColumnObject instanceof Collection) {
            	for(Object obj : (Collection)superColumnObject) {
            		superColumn.setName(superColumnField.getName() + Constants.SUPER_COLUMN_NAME_DELIMITER + UUID.randomUUID().toString());		//TODO: Change this to correct format
            		SuperColumn thriftSuperColumn = buildThriftSuperColumn(timestamp, superColumn, obj);
            		tr.addSuperColumn(thriftSuperColumn);
            	}
            	
            } else {
            	SuperColumn thriftSuperColumn = buildThriftSuperColumn(timestamp, superColumn, superColumnObject);
                tr.addSuperColumn(thriftSuperColumn);            	
            }         
            
        }
    }
    
    private SuperColumn buildThriftSuperColumn(long timestamp, EntityMetadata.SuperColumn superColumn, Object superColumnObject) throws PropertyAccessException {
    	List<Column> thriftColumns = new ArrayList<Column>();  
    	for (EntityMetadata.Column column : superColumn.getColumns()) {
            Field field = column.getField();
            String name = column.getName();

            try {
                byte[] value = PropertyAccessorHelper.get(superColumnObject, field);
                if (null != value) {
                    Column thriftColumn = new Column();
                    thriftColumn.setName(PropertyAccessorFactory.STRING.toBytes(name));
                    thriftColumn.setValue(value);
                    thriftColumn.setTimestamp(timestamp);
                    thriftColumns.add(thriftColumn);
                }
            } catch (PropertyAccessException exp) {
                log.warn(exp.getMessage());
            }
        }
        SuperColumn thriftSuperColumn = new SuperColumn();        
        thriftSuperColumn.setName(PropertyAccessorFactory.STRING.toBytes(superColumn.getName()));
        thriftSuperColumn.setColumns(thriftColumns);      
        
        return thriftSuperColumn;
    }
    
    
    /**
     * Splits foreign keys into Set. 
     * @param foreignKeys the foreign keys
     * @return the set
     */
	private Set<String> deserializeKeys(String foreignKeys) {
		Set<String> keys = new HashSet<String>();

		if (null == foreignKeys || foreignKeys.isEmpty()) {
			return keys;
		}

		String array[] = foreignKeys.split(Constants.SEPARATOR);
		for (String element : array) {
			keys.add(element);
		}
		return keys;
	}

    /**
     * Creates a string representation of a set of foreign keys by combining
     * them together separated by "~" character.
     * Note: Assumption is that @Id will never contain "~" character. Checks for
     * this are not added yet.
     * @param foreignKeys the foreign keys
     * @return the string
     */
	private String serializeKeys(Set<String> foreignKeys) {
		if (null == foreignKeys || foreignKeys.isEmpty()) {
			return null;
		}

		StringBuilder sb = new StringBuilder();
		for (String key : foreignKeys) {
			if (sb.length() > 0) {
				sb.append(Constants.SEPARATOR);
			}
			sb.append(key);
		}
		return sb.toString();
    }

}

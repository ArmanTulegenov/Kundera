/*******************************************************************************
 * * Copyright 2013 Impetus Infotech.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 ******************************************************************************/
package com.impetus.kundera.persistence.event;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import javax.persistence.PostPersist;
import javax.persistence.PrePersist;

import junit.framework.Assert;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.impetus.kundera.metadata.KunderaMetadataManager;
import com.impetus.kundera.metadata.model.EntityMetadata;
import com.impetus.kundera.metadata.model.KunderaMetadata;

/**
 * Test case for {@link EntityEventDispatcher}
 * 
 * @author amresh.singh
 * 
 */
public class EntityEventDispatcherTest
{

    private EntityEventDispatcher eventDispatcher;

    private EntityManager em;

    private EntityManagerFactory emf;

    /**
     * @throws java.lang.Exception
     */
    @Before
    public void setUp() throws Exception
    {
        eventDispatcher = new EntityEventDispatcher();
        KunderaMetadata.INSTANCE.setApplicationMetadata(null);
        emf = Persistence.createEntityManagerFactory("kunderatest");
        em = emf.createEntityManager();
    }

    /**
     * @throws java.lang.Exception
     */
    @After
    public void tearDown() throws Exception
    {
        eventDispatcher = null;
        em.close();
        emf.close();
    }

    /**
     * Test method for
     * {@link com.impetus.kundera.persistence.event.EntityEventDispatcher#fireEventListeners(com.impetus.kundera.metadata.model.EntityMetadata, java.lang.Object, java.lang.Class)}
     * .
     */
    @Test
    public void testFireEventListeners()
    {
        PersonEventDispatch person = new PersonEventDispatch("1", "John", "Smith");
        EntityMetadata m = KunderaMetadataManager.getEntityMetadata(person.getClass());
        eventDispatcher.fireEventListeners(m, person, PrePersist.class);
        Assert.assertEquals("Amresh", person.getFirstName());
        Assert.assertEquals("Smith", person.getLastName());
        eventDispatcher.fireEventListeners(m, person, PostPersist.class);
        Assert.assertEquals("Amresh", person.getFirstName());
        Assert.assertEquals("Singh", person.getLastName());
    }

}

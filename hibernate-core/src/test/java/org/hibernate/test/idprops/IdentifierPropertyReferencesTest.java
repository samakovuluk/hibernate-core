/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * Copyright (c) 2011, Red Hat Inc. or third-party contributors as
 * indicated by the @author tags or express copyright attribution
 * statements applied by the authors.  All third-party contributions are
 * distributed under license by Red Hat Inc.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.hibernate.test.idprops;

import org.junit.Test;

import org.hibernate.Criteria;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.criterion.Projections;
import org.hibernate.criterion.Restrictions;
import org.hibernate.testing.FailureExpectedWithNewMetamodel;
import org.hibernate.testing.junit4.BaseCoreFunctionalTestCase;

import static org.junit.Assert.assertEquals;

/**
 * @author Steve Ebersole
 */
@FailureExpectedWithNewMetamodel
public class IdentifierPropertyReferencesTest extends BaseCoreFunctionalTestCase {
	@Override
	public String[] getMappings() {
		return new String[] { "idprops/Mapping.hbm.xml" };
	}

	@Test
	public void testHqlIdPropertyReferences() {
		Session s = openSession();
		s.beginTransaction();
		Person p = new Person( new Long(1), "steve", 123 );
		s.save( p );
		Order o = new Order( new Long(1), p );
		LineItem l = new LineItem( o, "my-product", 2 );
		l.setId( "456" );
		s.save( o );
		s.getTransaction().commit();
		s.close();

		s = openSession();
		s.beginTransaction();

		long count = extractCount( s, "select count(*) from Person p where p.id = 123" );
		assertEquals( "Person by id prop (non-identifier)", 1, count );
		count = extractCount( s, "select count(*) from Person p where p.pk = 1" );
		assertEquals( "Person by pk prop (identifier)", 1, count );

		count = extractCount( s, "select count(*) from Order o where o.id = 1" );
		assertEquals( "Order by number prop (named identifier)", 1, count );
		count = extractCount( s, "select count(*) from Order o where o.number = 1" );
		assertEquals( "Order by id prop (virtual identifier)", 1, count );

		count = extractCount( s, "select count(*) from LineItem l where l.id = '456'" );
		assertEquals( "LineItem by id prop (non-identifier", 1, count );

		if ( getDialect().supportsRowValueConstructorSyntax() ) {
			Query q = s.createQuery( "select count(*) from LineItem l where l.pk = (:order, :product)" )
					.setEntity( "order", o )
					.setString( "product", "my-product" );
			count = extractCount( q );
			assertEquals( "LineItem by pk prop (named composite identifier", 1, count );
		}

		count = extractCount( s, "select count(*) from Order o where o.orderee.id = 1" );
		assertEquals( 0, count );
		count = extractCount( s, "select count(*) from Order o where o.orderee.pk = 1" );
		assertEquals( 1, count );
		count = extractCount( s, "select count(*) from Order o where o.orderee.id = 123" );
		assertEquals( 1, count );

		count = extractCount( s, "select count(*) from LineItem l where l.pk.order.id = 1" );
		assertEquals( 1, count );
		count = extractCount( s, "select count(*) from LineItem l where l.pk.order.number = 1" );
		assertEquals( 1, count );
		count = extractCount( s, "select count(*) from LineItem l where l.pk.order.orderee.pk = 1" );
		assertEquals( 1, count );

		s.delete( o );
		s.delete( p );
		s.getTransaction().commit();
		s.close();
	}

	@Test
	public void testCriteriaIdPropertyReferences() {
		Session s = openSession();
		s.beginTransaction();
		Person p = new Person( new Long(1), "steve", 123 );
		s.save( p );
		Order o = new Order( new Long(1), p );
		LineItem l = new LineItem( o, "my-product", 2 );
		l.setId( "456" );
		s.save( o );
		s.getTransaction().commit();
		s.close();

		s = openSession();
		s.beginTransaction();

		Criteria crit = s.createCriteria( Person.class );
		crit.setProjection( Projections.rowCount() );
		crit.add( Restrictions.eq( "id", new Integer(123) ) );
		long count = extractCount( crit );
		assertEquals( "Person by id prop (non-identifier)", 1, count );

		crit = s.createCriteria( Person.class );
		crit.setProjection( Projections.rowCount() );
		crit.add( Restrictions.eq( "pk", new Long(1) ) );
		count = extractCount( crit );
		assertEquals( "Person by pk prop (identifier)", 1, count );

		crit = s.createCriteria( Order.class );
		crit.setProjection( Projections.rowCount() );
		crit.add(  Restrictions.eq( "number", new Long(1) ) );
		count = extractCount( crit );
		assertEquals( "Order by number prop (named identifier)", 1, count );

		crit = s.createCriteria( Order.class );
		crit.setProjection( Projections.rowCount() );
		crit.add(  Restrictions.eq( "id", new Long(1) ) );
		count = extractCount( crit );
		assertEquals( "Order by id prop (virtual identifier)", 1, count );

		crit = s.createCriteria( LineItem.class );
		crit.setProjection( Projections.rowCount() );
		crit.add(  Restrictions.eq( "id", "456" ) );
		count = extractCount( crit );
		assertEquals( "LineItem by id prop (non-identifier", 1, count );

		if ( getDialect().supportsRowValueConstructorSyntax() ) {
			crit = s.createCriteria( LineItem.class );
			crit.setProjection( Projections.rowCount() );
			crit.add( Restrictions.eq( "pk", new LineItemPK( o, "my-product" ) ) );
			count = extractCount( crit );
			assertEquals( "LineItem by pk prop (named composite identifier)", 1, count );
		}

		crit = s.createCriteria( Order.class );
		crit.setProjection( Projections.rowCount() );
		crit.createAlias( "orderee", "p" ).add( Restrictions.eq( "p.id", new Integer(1) ) );
		count = extractCount( crit );
		assertEquals( 0, count );

		crit = s.createCriteria( Order.class );
		crit.setProjection( Projections.rowCount() );
		crit.createAlias( "orderee", "p" ).add( Restrictions.eq( "p.pk", new Long(1) ) );
		count = extractCount( crit );
		assertEquals( 1, count );

		crit = s.createCriteria( Order.class );
		crit.setProjection( Projections.rowCount() );
		crit.createAlias( "orderee", "p" ).add( Restrictions.eq( "p.id", new Integer(123) ) );
		count = extractCount( crit );
		assertEquals( 1, count );

		crit = s.createCriteria( LineItem.class );
		crit.setProjection( Projections.rowCount() );
		crit.add( Restrictions.eq( "pk.order.id", new Long(1) ) );
		count = extractCount( crit );
		assertEquals( 1, count );

		crit = s.createCriteria( LineItem.class );
		crit.setProjection( Projections.rowCount() );
		crit.add( Restrictions.eq( "pk.order.number", new Long(1) ) );
		count = extractCount( crit );
		assertEquals( 1, count );

		s.delete( o );
		s.delete( p );
		s.getTransaction().commit();
		s.close();
	}

	private long extractCount(Session s, String hql) {
		return extractCount( s.createQuery( hql ) );
	}

	private long extractCount(Query query) {
		return ( ( Long ) query.list().get( 0 ) ).longValue();
	}

	private long extractCount(Criteria crit) {
		return ( ( Long ) crit.list().get( 0 ) ).longValue();
	}
}

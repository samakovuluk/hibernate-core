/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.event.internal;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import org.jboss.logging.Logger;

import org.hibernate.boot.registry.selector.spi.StrategySelector;
import org.hibernate.engine.config.spi.ConfigurationService;
import org.hibernate.engine.spi.OperationContextType;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.spi.AbstractEvent;
import org.hibernate.event.spi.EntityCopyObserver;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.MergeEvent;
import org.hibernate.pretty.MessageHelper;
import org.hibernate.service.ServiceRegistry;

/**
 * MergeContext is a Map implementation that is intended to be used by a merge
 * event listener to keep track of each entity being merged and their corresponding
 * managed result. Entities to be merged may to be added to the MergeContext before
 * the merge operation has cascaded to that entity.
 *
 * "Merge entity" and "mergeEntity" method parameter refer to an entity that is (or will be)
 * merged via {@link org.hibernate.event.spi.EventSource#merge(Object mergeEntity)}.
 *
 * "Managed entity" and "managedEntity" method parameter refer to the managed entity that is
 * the result of merging an entity.
 *
 * A merge entity can be transient, detached, or managed. If it is managed, then it must be
 * the same as its associated entity result.
 *
 * If {@link #put(Object mergeEntity, Object managedEntity)} is called, and this
 * MergeContext already contains an entry with a different entity as the key, but
 * with the same (managedEntity) value, this means that multiple entity representations
 * for the same persistent entity are being merged. If this happens,
 * {@link org.hibernate.event.spi.EntityCopyObserver#entityCopyDetected(
 * Object managedEntity, Object mergeEntity1, Object mergeEntity2, org.hibernate.event.spi.EventSource)}
 * will be called. It is up to that method to determine the property course of
 * action for this situation.
 *
 * There are several restrictions.
 * <ul>
 *     <li>Methods that return collections (e.g., {@link #keySet()},
 *          {@link #values()}) return an
 *          unnmodifiable view of the collection;</li>
 *     <li>If {@link #put(Object mergeEntity, Object) managedEntity} or
 *         {@link #put(Object mergeEntity, Object managedEntity, boolean isOperatedOn)}
 *         is executed and this MergeMap already contains a cross-reference for
 *         <code>mergeEntity</code>, then <code>managedEntity</code> must be the
 *         same as what is already associated with <code>mergeEntity</code> in this
 *         MergeContext.
 *     </li>
 *      <li>the Map returned by {@link #invertMap()} will only contain the
 *          managed-to-merge entity cross-reference to its "newest"
 *          (most recently added) merge entity.</li>
 * </ul>
 * <p>
 * The following method is intended to be used by a merge event listener (and other
 * classes) in the same package to add a merge entity and its corresponding
 * managed entity to a MergeContext and indicate if the merge operation is
 * being performed on the merge entity yet.<p/>
 * {@link MergeOperationContext#put(Object mergeEntity, Object managedEntity, boolean isOperatedOn)}
 * <p/>
 * The following method is intended to be used by a merge event listener (and other
 * classes) in the same package to indicate whether the merge operation is being
 * performed on a merge entity already in the MergeContext:
 * {@link MergeOperationContext#setOperatedOn(Object mergeEntity, boolean isOperatedOn)
 *
 * @author Gail Badner
 */
public class MergeOperationContext extends AbstractSaveOperationContext<MergeEvent> {
	private static final Logger LOG = Logger.getLogger( MergeOperationContext.class );

	private EntityCopyObserver entityCopyObserver;

	private Map<Object,Object> mergeToManagedEntityXref = new IdentityHashMap<Object,Object>(10);
		// key is an entity to be merged;
		// value is the associated managed entity (result) in the persistence context.

	private Map<Object,Object> managedToMergeEntityXref = new IdentityHashMap<Object,Object>( 10 );
		// maintains the inverse of the mergeToManagedEntityXref for performance reasons.
		// key is the managed entity result in the persistence context.
		// value is the associated entity to be merged; if multiple
		// representations of the same persistent entity are added to the MergeContext,
		// value will be the most recently added merge entity that is
		// associated with the managed entity.

	// TODO: merge mergeEntityToOperatedOnFlagMap into mergeToManagedEntityXref, since they have the same key.
	//       need to check if this would hurt performance.
	private Map<Object,Boolean> mergeEntityToOperatedOnFlagMap = new IdentityHashMap<Object,Boolean>( 10 );

	public MergeOperationContext() {
		super( MergeEvent.class );
	}

	// key is a merge entity;
	    // value is a flag indicating if the merge entity is currently in the merge process.

	@Override
	public OperationContextType getOperationContextType() {
		return OperationContextType.MERGE;
	}

	@Override
	public void beforeOperation(MergeEvent event) {
		if ( entityCopyObserver == null ) {
			entityCopyObserver = createEntityCopyObserver( event.getSession().getFactory() );
		}
		super.beforeOperation( event );
	}

	@Override
	public void afterOperation(MergeEvent event) {
		entityCopyObserver.topLevelMergeComplete( getSession() );
		super.afterOperation( event );
	}

	private static EntityCopyObserver createEntityCopyObserver(SessionFactoryImplementor sessionFactory) {
		final ServiceRegistry serviceRegistry = sessionFactory.getServiceRegistry();
		final ConfigurationService configurationService
				= serviceRegistry.getService( ConfigurationService.class );
		String entityCopyObserverStrategy = configurationService.getSetting(
				"hibernate.event.merge.entity_copy_observer",
				new ConfigurationService.Converter<String>() {
					@Override
					public String convert(Object value) {
						return value.toString();
					}
				},
				EntityCopyNotAllowedObserver.SHORT_NAME
		);
		LOG.debugf( "EntityCopyObserver strategy: %s", entityCopyObserverStrategy );
		final StrategySelector strategySelector = serviceRegistry.getService( StrategySelector.class );
		return strategySelector.resolveStrategy( EntityCopyObserver.class, entityCopyObserverStrategy );
	}

	/**
	 * Clears the MergeContext.
	 */
	public void clear() {
		mergeToManagedEntityXref.clear();
		managedToMergeEntityXref.clear();
		mergeEntityToOperatedOnFlagMap.clear();
		entityCopyObserver.clear();
		super.clear();
	}

	/**
	 * Returns true if this MergeContext contains a cross-reference for the specified merge entity
	 * to a managed entity result.
	 *
	 * @param mergeEntity must be non-null
	 * @return true if this MergeContext contains a cross-reference for the specified merge entity
	 * @throws NullPointerException if mergeEntity is null
	 */
	boolean containsMergeEntity(Object mergeEntity) {
		if ( mergeEntity == null ) {
			throw new NullPointerException( "null entities are not supported by " + getClass().getName() );
		}
		return mergeToManagedEntityXref.containsKey( mergeEntity );
	}

	/**
	 * Returns true if this MergeContext contains a cross-reference from the specified managed entity
	 * to a merge entity.
	 * @param managedEntity must be non-null
	 * @return true if this MergeContext contains a cross-reference from the specified managed entity
	 * to a merge entity
	 * @throws NullPointerException if managedEntity is null
	 */
	public boolean containsValue(Object managedEntity) {
		if ( managedEntity == null ) {
			throw new NullPointerException( "null copies are not supported by " + getClass().getName() );
		}
		return managedToMergeEntityXref.containsKey( managedEntity );
	}

	/**
	 * Should only be used for testing.
	 *
	 * Returns an unmodifiable set view of the merge-to-managed entity cross-references contained in this MergeContext.
	 * @return an unmodifiable set view of the merge-to-managed entity cross-references contained in this MergeContext
	 *
	 * @see {@link Collections#unmodifiableSet(java.util.Set)}
	 *
	 */
	/* package-private */ Set entrySet() {
		return Collections.unmodifiableSet( mergeToManagedEntityXref.entrySet() );
	}

	/**
	 * Returns the managed entity associated with the specified merge Entity.
	 * @param mergeEntity the merge entity; must be non-null
	 * @return  the managed entity associated with the specified merge Entity
	 * @throws NullPointerException if mergeEntity is null
	 */
	public Object get(Object mergeEntity) {
		if ( mergeEntity == null ) {
			throw new NullPointerException( "null entities are not supported by " + getClass().getName() );
		}
		return mergeToManagedEntityXref.get( mergeEntity );
	}


	/**
	 * Should only be used for testing.
	 *
	 * Returns an unmodifiable set view of the merge entities contained in this MergeContext
	 * @return an unmodifiable set view of the merge entities contained in this MergeContext
	 *
	 * @see {@link Collections#unmodifiableSet(java.util.Set)}
	 */
	/* package-private */ Set keySet() {
		return Collections.unmodifiableSet( mergeToManagedEntityXref.keySet() );
	}

	/**
	 * Associates the specified merge entity with the specified managed entity result in this MergeContext.
	 * If this MergeContext already contains a cross-reference for <code>mergeEntity</code> when this
	 * method is called, then <code>managedEntity</code> must be the same as what is already associated
	 * with <code>mergeEntity</code>.
	 * <p/>
	 * This method assumes that the merge process is not yet operating on <code>mergeEntity</code>.
	 * Later when <code>mergeEntity</code> enters the merge process, {@link #setOperatedOn(Object, boolean)}
	 * should be called.
	 * <p/>
	 * @param mergeEntity the merge entity; must be non-null
	 * @param managedEntity the managed entity result; must be non-null
	 * @return previous managed entity associated with specified merge entity, or null if
	 * there was no mapping for mergeEntity.
	 * @throws NullPointerException if mergeEntity or managedEntity is null
	 * @throws IllegalArgumentException if <code>managedEntity</code> is not the same as the previous
	 * managed entity associated with <code>merge entity</code>
	 * @throws IllegalStateException if internal cross-references are out of sync,
	 */
	public Object put(Object mergeEntity, Object managedEntity) {
		return put( mergeEntity, managedEntity, Boolean.FALSE );
	}

	/**
	 * Associates the specified merge entity with the specified managed entity in this MergeContext.
	 * If this MergeContext already contains a cross-reference for <code>mergeEntity</code> when this
	 * method is called, then <code>managedEntity</code> must be the same as what is already associated
	 * with <code>mergeEntity</code>.
	 *
	 * @param mergeEntity the mergge entity; must be non-null
	 * @param managedEntity the managed entity; must be non-null
	 * @param isOperatedOn indicates if the merge operation is performed on the mergeEntity.
	 *
	 * @return previous managed entity associated with specified merge entity, or null if
	 * there was no mapping for mergeEntity.
	 * @throws NullPointerException if mergeEntity or managedEntity is null
	 * @throws IllegalArgumentException if <code>managedEntity</code> is not the same as the previous
	 * managed entity associated with <code>mergeEntity</code>
	 * @throws IllegalStateException if internal cross-references are out of sync,
	 */
	/* package-private */ Object put(Object mergeEntity, Object managedEntity, boolean isOperatedOn) {
		if ( mergeEntity == null || managedEntity == null ) {
			throw new NullPointerException( "null merge and managed entities are not supported by " + getClass().getName() );
		}

		Object oldManagedEntity = mergeToManagedEntityXref.put( mergeEntity, managedEntity );
		Boolean oldOperatedOn = mergeEntityToOperatedOnFlagMap.put( mergeEntity, isOperatedOn );
		// If managedEntity already corresponds with a different merge entity, that means
		// that there are multiple entities being merged that correspond with managedEntity.
		// In the following, oldMergeEntity will be replaced with mergeEntity in managedToMergeEntityXref.
		Object oldMergeEntity = managedToMergeEntityXref.put( managedEntity, mergeEntity );

		if ( oldManagedEntity == null ) {
			// this is a new mapping for mergeEntity in mergeToManagedEntityXref
			if  ( oldMergeEntity != null ) {
				// oldMergeEntity was a different merge entity with the same corresponding managed entity;
				entityCopyObserver.entityCopyDetected(
						managedEntity,
						mergeEntity,
						oldMergeEntity,
						getSession()
				);
			}
			if ( oldOperatedOn != null ) {
				throw new IllegalStateException(
						"MergeContext#mergeEntityToOperatedOnFlagMap contains an merge entity " + printEntity( mergeEntity )
								+ ", but MergeContext#mergeToManagedEntityXref does not."
				);
			}
		}
		else {
			// mergeEntity was already mapped in mergeToManagedEntityXref
			if ( oldManagedEntity != managedEntity ) {
				throw new IllegalArgumentException(
						"Error occurred while storing a merge Entity " + printEntity( mergeEntity )
								+ ". It was previously associated with managed entity " + printEntity( oldManagedEntity )
								+ ". Attempted to replace managed entity with " + printEntity( managedEntity )
				);
			}
			if ( oldOperatedOn == null ) {
				throw new IllegalStateException(
						"MergeContext#mergeToManagedEntityXref contained an mergeEntity " + printEntity( mergeEntity )
								+ ", but MergeContext#mergeEntityToOperatedOnFlagMap did not."
				);
			}
		}

		return oldManagedEntity;
	}

	/**
	 * Returns the number of merge-to-managed entity cross-references in this MergeContext
	 * @return the number of merge-to-managed entity cross-references in this MergeContext
	 */
	public int size() {
		return mergeToManagedEntityXref.size();
	}

	/**
	 * Should only be used for testing.
	 *
	 * Returns an unmodifiable Set view of managed entities contained in this MergeContext.
	 * @return an unmodifiable Set view of managed entities contained in this MergeContext
	 *
	 * @see {@link Collections#unmodifiableSet(java.util.Set)}
	 */
	/* package-private */ Collection values() {
		return Collections.unmodifiableSet( managedToMergeEntityXref.keySet() );
	}

	/**
	 * Returns true if the listener is performing the merge operation on the specified merge entity.
	 * @param mergeEntity the merge entity; must be non-null
	 * @return true if the listener is performing the merge operation on the specified merge entity;
	 * false, if there is no mapping for mergeEntity.
	 * @throws NullPointerException if mergeEntity is null
	 */
	public boolean isOperatedOn(Object mergeEntity) {
		if ( mergeEntity == null ) {
			throw new NullPointerException( "null merge entities are not supported by " + getClass().getName() );
		}
		final Boolean isOperatedOn = mergeEntityToOperatedOnFlagMap.get( mergeEntity );
		return isOperatedOn == null ? false : isOperatedOn;
	}

	/**
	 * Set flag to indicate if the listener is performing the merge operation on the specified merge entity.
	 * @param mergeEntity must be non-null and this MergeContext must contain a cross-reference for mergeEntity
	 *                       to a managed entity
	 * @throws NullPointerException if mergeEntity is null
	 * @throws IllegalStateException if this MergeContext does not contain a a cross-reference for mergeEntity
	 */
	/* package-private */ void setOperatedOn(Object mergeEntity, boolean isOperatedOn) {
		if ( mergeEntity == null ) {
			throw new NullPointerException( "null entities are not supported by " + getClass().getName() );
		}
		if ( ! mergeEntityToOperatedOnFlagMap.containsKey( mergeEntity ) ||
			! mergeToManagedEntityXref.containsKey( mergeEntity ) ) {
			throw new IllegalStateException( "called MergeContext#setOperatedOn() for mergeEntity not found in MergeContext" );
		}
		mergeEntityToOperatedOnFlagMap.put( mergeEntity, isOperatedOn );
	}

	/**
	 * Returns an unmodifiable map view of the managed-to-merge entity
	 * cross-references.
	 *
	 * The returned Map will contain a cross-reference from each managed entity
	 * to the most recently associated merge entity that was most recently put in the MergeContext.
	 *
	 * @return an unmodifiable map view of the managed-to-merge entity cross-references.
	 *
	 * @see {@link Collections#unmodifiableMap(java.util.Map)}
	 */
	public Map invertMap() {
		return Collections.unmodifiableMap( managedToMergeEntityXref );
	}

	private String printEntity(Object entity) {
		if ( getSession().getPersistenceContext().getEntry( entity ) != null ) {
			return MessageHelper.infoString( getSession().getEntityName( entity ), getSession().getIdentifier( entity ) );
		}
		// Entity was not found in current persistence context. Use Object#toString() method.
		return "[" + entity + "]";
	}
}

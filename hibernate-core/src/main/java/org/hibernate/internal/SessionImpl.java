/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Reader;
import java.io.Serializable;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.NClob;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.persistence.EntityNotFoundException;
import javax.transaction.SystemException;

import org.hibernate.CacheMode;
import org.hibernate.ConnectionReleaseMode;
import org.hibernate.Criteria;
import org.hibernate.EmptyInterceptor;
import org.hibernate.EntityNameResolver;
import org.hibernate.Filter;
import org.hibernate.FlushMode;
import org.hibernate.HibernateException;
import org.hibernate.IdentifierLoadAccess;
import org.hibernate.Interceptor;
import org.hibernate.LobHelper;
import org.hibernate.LockMode;
import org.hibernate.LockOptions;
import org.hibernate.MappingException;
import org.hibernate.MultiIdentifierLoadAccess;
import org.hibernate.NaturalIdLoadAccess;
import org.hibernate.ObjectDeletedException;
import org.hibernate.ObjectNotFoundException;
import org.hibernate.Query;
import org.hibernate.QueryException;
import org.hibernate.ReplicationMode;
import org.hibernate.SQLQuery;
import org.hibernate.ScrollMode;
import org.hibernate.ScrollableResults;
import org.hibernate.SessionBuilder;
import org.hibernate.SessionEventListener;
import org.hibernate.SessionException;
import org.hibernate.SharedSessionBuilder;
import org.hibernate.SimpleNaturalIdLoadAccess;
import org.hibernate.Transaction;
import org.hibernate.TransactionException;
import org.hibernate.TransientObjectException;
import org.hibernate.TypeHelper;
import org.hibernate.UnknownProfileException;
import org.hibernate.UnresolvableObjectException;
import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.criterion.NaturalIdentifier;
import org.hibernate.engine.internal.OperationContextManager;
import org.hibernate.engine.internal.SessionEventListenerManagerImpl;
import org.hibernate.engine.internal.StatefulPersistenceContext;
import org.hibernate.engine.jdbc.LobCreator;
import org.hibernate.engine.jdbc.NonContextualLobCreator;
import org.hibernate.engine.jdbc.internal.JdbcCoordinatorImpl;
import org.hibernate.engine.jdbc.spi.JdbcCoordinator;
import org.hibernate.engine.query.spi.FilterQueryPlan;
import org.hibernate.engine.query.spi.HQLQueryPlan;
import org.hibernate.engine.query.spi.NativeSQLQueryPlan;
import org.hibernate.engine.query.spi.sql.NativeSQLQuerySpecification;
import org.hibernate.engine.spi.ActionQueue;
import org.hibernate.engine.spi.CollectionEntry;
import org.hibernate.engine.spi.EntityEntry;
import org.hibernate.engine.spi.EntityKey;
import org.hibernate.engine.spi.LoadQueryInfluencers;
import org.hibernate.engine.spi.OperationContext;
import org.hibernate.engine.spi.OperationContextType;
import org.hibernate.engine.spi.PersistenceContext;
import org.hibernate.engine.spi.QueryParameters;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SessionOwner;
import org.hibernate.engine.spi.Status;
import org.hibernate.engine.transaction.jta.platform.spi.JtaPlatform;
import org.hibernate.engine.transaction.spi.TransactionObserver;
import org.hibernate.event.service.spi.EventListenerGroup;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.AutoFlushEvent;
import org.hibernate.event.spi.AutoFlushEventListener;
import org.hibernate.event.spi.ClearEvent;
import org.hibernate.event.spi.ClearEventListener;
import org.hibernate.event.spi.DeleteEvent;
import org.hibernate.event.spi.DeleteEventListener;
import org.hibernate.event.spi.DirtyCheckEvent;
import org.hibernate.event.spi.DirtyCheckEventListener;
import org.hibernate.event.spi.EventSource;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.EvictEvent;
import org.hibernate.event.spi.EvictEventListener;
import org.hibernate.event.spi.FlushEvent;
import org.hibernate.event.spi.FlushEventListener;
import org.hibernate.event.spi.InitializeCollectionEvent;
import org.hibernate.event.spi.InitializeCollectionEventListener;
import org.hibernate.event.spi.LoadEvent;
import org.hibernate.event.spi.LoadEventListener;
import org.hibernate.event.spi.LoadEventListener.LoadType;
import org.hibernate.event.spi.LockEvent;
import org.hibernate.event.spi.LockEventListener;
import org.hibernate.event.spi.MergeEvent;
import org.hibernate.event.spi.MergeEventListener;
import org.hibernate.event.spi.PersistEvent;
import org.hibernate.event.spi.PersistEventListener;
import org.hibernate.event.spi.RefreshEvent;
import org.hibernate.event.spi.RefreshEventListener;
import org.hibernate.event.spi.ReplicateEvent;
import org.hibernate.event.spi.ReplicateEventListener;
import org.hibernate.event.spi.ResolveNaturalIdEvent;
import org.hibernate.event.spi.ResolveNaturalIdEventListener;
import org.hibernate.event.spi.SaveOrUpdateEvent;
import org.hibernate.event.spi.SaveOrUpdateEventListener;
import org.hibernate.internal.CriteriaImpl.CriterionEntry;
import org.hibernate.jdbc.ReturningWork;
import org.hibernate.jdbc.Work;
import org.hibernate.jdbc.WorkExecutor;
import org.hibernate.jdbc.WorkExecutorVisitable;
import org.hibernate.loader.criteria.CriteriaLoader;
import org.hibernate.loader.custom.CustomLoader;
import org.hibernate.loader.custom.CustomQuery;
import org.hibernate.loader.entity.DynamicBatchingEntityLoaderBuilder;
import org.hibernate.persister.collection.CollectionPersister;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.persister.entity.MultiLoadOptions;
import org.hibernate.persister.entity.OuterJoinLoadable;
import org.hibernate.pretty.MessageHelper;
import org.hibernate.procedure.ProcedureCall;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.LazyInitializer;
import org.hibernate.resource.jdbc.spi.JdbcSessionContext;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.hibernate.resource.transaction.TransactionCoordinator;
import org.hibernate.resource.transaction.backend.jta.internal.JtaTransactionCoordinatorImpl;
import org.hibernate.resource.transaction.backend.jta.internal.synchronization.AfterCompletionAction;
import org.hibernate.resource.transaction.backend.jta.internal.synchronization.ExceptionMapper;
import org.hibernate.resource.transaction.backend.jta.internal.synchronization.ManagedFlushChecker;
import org.hibernate.resource.transaction.spi.TransactionStatus;
import org.hibernate.stat.SessionStatistics;
import org.hibernate.stat.internal.SessionStatisticsImpl;

/**
 * Concrete implementation of a Session.
 * <p/>
 * Exposes two interfaces:<ul>
 * <li>{@link org.hibernate.Session} to the application</li>
 * <li>{@link org.hibernate.engine.spi.SessionImplementor} to other Hibernate components (SPI)</li>
 * </ul>
 * <p/>
 * This class is not thread-safe.
 *
 * @author Gavin King
 * @author Steve Ebersole
 * @author Brett Meyer
 */
public final class SessionImpl extends AbstractSessionImpl implements EventSource {

	// todo : need to find a clean way to handle the "event source" role
	// a separate class responsible for generating/dispatching events just duplicates most of the Session methods...
	// passing around separate interceptor, factory, actionQueue, and persistentContext is not manageable...

	private static final CoreMessageLogger LOG = CoreLogging.messageLogger( SessionImpl.class );
	private static final boolean TRACE_ENABLED = LOG.isTraceEnabled();

	private transient long timestamp;

	private transient SessionOwner sessionOwner;

	private transient ActionQueue actionQueue;
	private transient StatefulPersistenceContext persistenceContext;
	private transient TransactionCoordinator transactionCoordinator;
	private transient JdbcCoordinatorImpl jdbcCoordinator;
	private transient Interceptor interceptor;
	private StatementInspector statementInspector;
	private transient EntityNameResolver entityNameResolver = new CoordinatingEntityNameResolver();

	private transient ConnectionReleaseMode connectionReleaseMode;
	private transient FlushMode flushMode = FlushMode.AUTO;
	private transient CacheMode cacheMode = CacheMode.NORMAL;

	private transient boolean autoClear; //for EJB3
	private transient boolean autoJoinTransactions = true;
	private transient boolean flushBeforeCompletionEnabled;
	private transient boolean autoCloseSessionEnabled;

	private transient int dontFlushFromFind;

	private transient LoadQueryInfluencers loadQueryInfluencers;

	private final transient boolean isTransactionCoordinatorShared;
	private transient TransactionObserver transactionObserver;

	private SessionEventListenerManagerImpl sessionEventsManager = new SessionEventListenerManagerImpl();

	private transient JdbcSessionContext jdbcSessionContext;

	private transient ExceptionMapper exceptionMapper;
	private transient ManagedFlushChecker managedFlushChecker;
	private transient AfterCompletionAction afterCompletionAction;
	private transient LoadEvent loadEvent; //cached LoadEvent instance

	private transient OperationContextManager operationContextManager = new OperationContextManager();;

	/**
	 * Constructor used for openSession(...) processing, as well as construction
	 * of sessions for getCurrentSession().
	 *
	 * @param connection The user-supplied connection to use for this session.
	 * @param factory The factory from which this session was obtained
	 * @param transactionCoordinator The transaction coordinator to use, may be null to indicate that a new transaction
	 * coordinator should get created.
	 * @param autoJoinTransactions Should the session automatically join JTA transactions?
	 * @param timestamp The timestamp for this session
	 * @param interceptor The interceptor to be applied to this session
	 * @param flushBeforeCompletionEnabled Should we auto flush before completion of transaction
	 * @param autoCloseSessionEnabled Should we auto close after completion of transaction
	 * @param connectionReleaseMode The mode by which we should release JDBC connections.
	 * @param tenantIdentifier The tenant identifier to use.  May be null
	 */
	SessionImpl(
			final Connection connection,
			final SessionFactoryImpl factory,
			final SessionOwner sessionOwner,
			final TransactionCoordinator transactionCoordinator,
			final JdbcCoordinatorImpl jdbcCoordinator,
			final Transaction transaction,
			final ActionQueue.TransactionCompletionProcesses transactionCompletionProcesses,
			final boolean autoJoinTransactions,
			final long timestamp,
			final Interceptor interceptor,
			final StatementInspector statementInspector,
			final boolean flushBeforeCompletionEnabled,
			final boolean autoCloseSessionEnabled,
			final ConnectionReleaseMode connectionReleaseMode,
			final String tenantIdentifier) {
		super( factory, tenantIdentifier );
		this.timestamp = timestamp;
		this.sessionOwner = sessionOwner;
		this.interceptor = interceptor == null ? EmptyInterceptor.INSTANCE : interceptor;
		this.actionQueue = new ActionQueue( this );
		this.persistenceContext = new StatefulPersistenceContext( this );

		this.autoCloseSessionEnabled = autoCloseSessionEnabled;
		this.flushBeforeCompletionEnabled = flushBeforeCompletionEnabled;

		initializeFromSessionOwner( sessionOwner );

		if ( statementInspector == null ) {
			this.statementInspector = new StatementInspector() {
				@Override
				@SuppressWarnings("deprecation")
				public String inspect(String sql) {
					return SessionImpl.this.interceptor.onPrepareStatement( sql );
				}
			};
		}
		else {
			this.statementInspector = statementInspector;
		}
		this.jdbcSessionContext = new JdbcSessionContextImpl( factory, this.statementInspector );

		if ( transactionCoordinator == null ) {
			this.isTransactionCoordinatorShared = false;
			this.connectionReleaseMode = connectionReleaseMode;
			this.autoJoinTransactions = autoJoinTransactions;

			this.jdbcCoordinator = new JdbcCoordinatorImpl( connection, this );
			this.transactionCoordinator = getTransactionCoordinatorBuilder().buildTransactionCoordinator(
					this.jdbcCoordinator,
					this
			);
			this.currentHibernateTransaction = getTransaction();
		}
		else {
			if ( connection != null ) {
				throw new SessionException( "Cannot simultaneously share transaction context and specify connection" );
			}
			this.transactionCoordinator = transactionCoordinator;
			this.jdbcCoordinator = jdbcCoordinator;
			this.currentHibernateTransaction = transaction;
			this.isTransactionCoordinatorShared = true;
			this.autoJoinTransactions = false;
			if ( transactionCompletionProcesses != null ) {
				actionQueue.setTransactionCompletionProcesses( transactionCompletionProcesses, true );
			}
			if ( autoJoinTransactions ) {
				LOG.debug(
						"Session creation specified 'autoJoinTransactions', which is invalid in conjunction " +
								"with sharing JDBC connection between sessions; ignoring"
				);
			}
			if ( connectionReleaseMode != this.jdbcCoordinator.getConnectionReleaseMode() ) {
				LOG.debug(
						"Session creation specified 'getConnectionReleaseMode', which is invalid in conjunction " +
								"with sharing JDBC connection between sessions; ignoring"
				);
			}
			this.connectionReleaseMode = this.jdbcCoordinator.getConnectionReleaseMode();

			transactionObserver = new TransactionObserver() {
				@Override
				public void afterBegin() {
				}

				@Override
				public void beforeCompletion() {
					if ( isOpen() && flushBeforeCompletionEnabled ) {
						SessionImpl.this.managedFlush();
					}
					actionQueue.beforeTransactionCompletion();
					try {
						SessionImpl.this.interceptor.beforeTransactionCompletion( currentHibernateTransaction );
					}
					catch (Throwable t) {
						LOG.exceptionInBeforeTransactionCompletionInterceptor( t );
					}
				}

				@Override
				public void afterCompletion(boolean successful, boolean delayed) {
					afterTransactionCompletion( successful, delayed );
					if ( !isClosed() && autoCloseSessionEnabled ) {
						managedClose();
					}
				}
			};

			transactionCoordinator.addObserver( transactionObserver );
		}

		loadQueryInfluencers = new LoadQueryInfluencers( factory );

		if ( factory.getStatistics().isStatisticsEnabled() ) {
			factory.getStatisticsImplementor().openSession();
		}

		if ( TRACE_ENABLED ) {
			LOG.tracef( "Opened session at timestamp: %s", timestamp );
		}

	}

	private void initializeFromSessionOwner(SessionOwner sessionOwner) {
		if ( sessionOwner != null ) {
			if ( sessionOwner.getExceptionMapper() != null ) {
				exceptionMapper = sessionOwner.getExceptionMapper();
			}
			else {
				exceptionMapper = STANDARD_EXCEPTION_MAPPER;
			}
			if ( sessionOwner.getAfterCompletionAction() != null ) {
				afterCompletionAction = sessionOwner.getAfterCompletionAction();
			}
			else {
				afterCompletionAction = STANDARD_AFTER_COMPLETION_ACTION;
			}
			if ( sessionOwner.getManagedFlushChecker() != null ) {
				managedFlushChecker = sessionOwner.getManagedFlushChecker();
			}
			else {
				managedFlushChecker = STANDARD_MANAGED_FLUSH_CHECKER;
			}
		}
		else {
			exceptionMapper = STANDARD_EXCEPTION_MAPPER;
			afterCompletionAction = STANDARD_AFTER_COMPLETION_ACTION;
			managedFlushChecker = STANDARD_MANAGED_FLUSH_CHECKER;
		}
	}

	@Override
	public SharedSessionBuilder sessionWithOptions() {
		return new SharedSessionBuilderImpl( this );
	}

	@Override
	public void clear() {
		errorIfClosed();
		// Do not call checkTransactionSynchStatus() here -- if a delayed
		// afterCompletion exists, it can cause an infinite loop.
		pulseTransactionCoordinator();
		internalClear();
	}

	private void internalClear() {
		persistenceContext.clear();
		actionQueue.clear();

		final ClearEvent event = new ClearEvent( this );
		for ( ClearEventListener listener : listeners( EventType.CLEAR ) ) {
			listener.onClear( event );
		}
		operationContextManager.clear();
	}

	@Override
	public long getTimestamp() {
		checkTransactionSynchStatus();
		return timestamp;
	}

	@Override
	public void close() throws HibernateException {
		LOG.trace( "Closing session" );
		if ( isClosed() ) {
			throw new SessionException( "Session was already closed" );
		}

		if ( factory.getStatistics().isStatisticsEnabled() ) {
			factory.getStatisticsImplementor().closeSession();
		}
		getEventListenerManager().end();

		try {
			if ( !isTransactionCoordinatorShared ) {
				jdbcCoordinator.close();
				return;
			}
			else {
				if ( getActionQueue().hasBeforeTransactionActions() || getActionQueue().hasAfterTransactionActions() ) {
					LOG.warn(
							"On close, shared Session had before / after transaction actions that have not yet been processed"
					);
				}
				return;
			}
		}
		finally {
			setClosed();
			cleanup();
		}
	}

	@Override
	public boolean isAutoCloseSessionEnabled() {
		return autoCloseSessionEnabled;
	}

	@Override
	public boolean shouldAutoJoinTransaction() {
		return autoJoinTransactions;
	}

	@Override
	public boolean isOpen() {
		checkTransactionSynchStatus();
		return !isClosed();
	}

	private boolean isFlushModeNever() {
		return FlushMode.isManualFlushMode( getFlushMode() );
	}

	private void managedFlush() {
		if ( isClosed() ) {
			LOG.trace( "Skipping auto-flush due to session closed" );
			return;
		}
		LOG.trace( "Automatically flushing session" );
		flush();
	}

	@Override
	public boolean shouldAutoClose() {
		if ( isClosed() ) {
			return false;
		}
		else if ( sessionOwner != null ) {
			return sessionOwner.shouldAutoCloseSession();
		}
		else {
			return isAutoCloseSessionEnabled();
		}
	}

	private void managedClose() {
		LOG.trace( "Automatically closing session" );
		close();
	}

	@Override
	public Connection connection() throws HibernateException {
		errorIfClosed();
		return this.jdbcCoordinator.getLogicalConnection().getPhysicalConnection();
	}

	@Override
	public boolean isConnected() {
		checkTransactionSynchStatus();
		return !isClosed() && this.jdbcCoordinator.getLogicalConnection().isOpen();
	}

	@Override
	public boolean isTransactionInProgress() {
		checkTransactionSynchStatus();
		return !isClosed() && transactionCoordinator.getTransactionDriverControl()
				.getStatus() == TransactionStatus.ACTIVE && transactionCoordinator.isJoined();
	}

	@Override
	public Connection disconnect() throws HibernateException {
		errorIfClosed();
		LOG.debug( "Disconnecting session" );
		return this.jdbcCoordinator.getLogicalConnection().manualDisconnect();
	}

	@Override
	public void reconnect(Connection conn) throws HibernateException {
		errorIfClosed();
		LOG.debug( "Reconnecting session" );
		checkTransactionSynchStatus();
		this.jdbcCoordinator.getLogicalConnection().manualReconnect( conn );
	}

	@Override
	public void setAutoClear(boolean enabled) {
		errorIfClosed();
		autoClear = enabled;
	}

	@Override
	public void disableTransactionAutoJoin() {
		errorIfClosed();
		autoJoinTransactions = false;
	}

	/**
	 * Check if there is a Hibernate or JTA transaction in progress and,
	 * if there is not, flush if necessary, make sure the connection has
	 * been committed (if it is not in autocommit mode) and run the after
	 * completion processing
	 *
	 * @param success Was the operation a success
	 */
	public void afterOperation(boolean success) {
		if ( !isTransactionInProgress() ) {
			jdbcCoordinator.afterTransaction();
		}
	}

	@Override
	public SessionEventListenerManagerImpl getEventListenerManager() {
		return sessionEventsManager;
	}

	@Override
	public void addEventListeners(SessionEventListener... listeners) {
		getEventListenerManager().addListener( listeners );
	}

	/**
	 * clear all the internal collections, just
	 * to help the garbage collector, does not
	 * clear anything that is needed during the
	 * afterTransactionCompletion() phase
	 */
	private void cleanup() {
		persistenceContext.clear();
	}

	@Override
	public LockMode getCurrentLockMode(Object object) throws HibernateException {
		errorIfClosed();
		checkTransactionSynchStatus();
		if ( object == null ) {
			throw new NullPointerException( "null object passed to getCurrentLockMode()" );
		}
		if ( object instanceof HibernateProxy ) {
			object = ( (HibernateProxy) object ).getHibernateLazyInitializer().getImplementation( this );
			if ( object == null ) {
				return LockMode.NONE;
			}
		}
		EntityEntry e = persistenceContext.getEntry( object );
		if ( e == null ) {
			throw new TransientObjectException( "Given object not associated with the session" );
		}
		if ( e.getStatus() != Status.MANAGED ) {
			throw new ObjectDeletedException(
					"The given object was deleted",
					e.getId(),
					e.getPersister().getEntityName()
			);
		}
		return e.getLockMode();
	}

	@Override
	public Object getEntityUsingInterceptor(EntityKey key) throws HibernateException {
		errorIfClosed();
		// todo : should this get moved to PersistentContext?
		// logically, is PersistentContext the "thing" to which an interceptor gets attached?
		final Object result = persistenceContext.getEntity( key );
		if ( result == null ) {
			final Object newObject = interceptor.getEntity( key.getEntityName(), key.getIdentifier() );
			if ( newObject != null ) {
				lock( newObject, LockMode.NONE );
			}
			return newObject;
		}
		else {
			return result;
		}
	}

	private void delayedAfterCompletion() {
		if ( transactionCoordinator instanceof JtaTransactionCoordinatorImpl ) {
			( (JtaTransactionCoordinatorImpl) transactionCoordinator ).getSynchronizationCallbackCoordinator()
					.processAnyDelayedAfterCompletion();
		}
	}

	@Override
	public boolean isOperationInProgress(OperationContextType operationContextType) {
		return operationContextManager.isOperationInProgress( operationContextType );
	}

	@Override
	public OperationContext getOperationContext(OperationContextType operationContextType) {
		return operationContextManager.getOperationContext( operationContextType );
	}

	// saveOrUpdate() operations ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public void saveOrUpdate(Object object) throws HibernateException {
		saveOrUpdate( null, object );
	}

	@Override
	public void saveOrUpdate(String entityName, Object obj) throws HibernateException {
		fireSaveOrUpdate( new SaveOrUpdateEvent( entityName, obj, this ), EventType.SAVE_UPDATE );
	}

	private void fireSaveOrUpdate(SaveOrUpdateEvent event, EventType<SaveOrUpdateEventListener> actualEventType) {
		errorIfClosed();
		checkTransactionSynchStatus();
		if ( !operationContextManager.isOperationInProgress( actualEventType ) ) {
			fireSaveOrUpdateTopLevel( event, actualEventType );
			return;  // early return
		}
		if ( TRACE_ENABLED ) {
			LOG.tracef(
					"%s operation cascade level: %d",
					actualEventType.eventName(),
					getPersistenceContext().getCascadeLevel() );
		}
		for ( SaveOrUpdateEventListener listener : listeners( actualEventType ) ) {
			listener.onSaveOrUpdate( event );
		}
		delayedAfterCompletion();
	}

	private void fireSaveOrUpdateTopLevel(SaveOrUpdateEvent event, EventType<SaveOrUpdateEventListener> actualEventType) {
		errorIfClosed();
		checkTransactionSynchStatus();
		operationContextManager.beforeOperation( actualEventType, event );
		boolean success = false;
		try {
			for ( SaveOrUpdateEventListener listener : listeners( actualEventType ) ) {
				listener.onSaveOrUpdate( event );
			}
			delayedAfterCompletion();
			success = true;
		}
		finally {
			operationContextManager.afterOperation( actualEventType, event, success );
		}
	}

	private <T> Iterable<T> listeners(EventType<T> type) {
		return eventListenerGroup( type ).listeners();
	}

	private <T> EventListenerGroup<T> eventListenerGroup(EventType<T> type) {
		return factory.getServiceRegistry().getService( EventListenerRegistry.class ).getEventListenerGroup( type );
	}


	// save() operations ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public Serializable save(Object obj) throws HibernateException {
		return save( null, obj );
	}

	@Override
	public Serializable save(String entityName, Object object) throws HibernateException {
		final SaveOrUpdateEvent event = new SaveOrUpdateEvent( entityName, object, this );
		fireSaveOrUpdate( event, EventType.SAVE );
		return event.getResultId();
	}

	// update() operations ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public void update(Object obj) throws HibernateException {
		update( null, obj );
	}

	@Override
	public void update(String entityName, Object object) throws HibernateException {
		fireSaveOrUpdate( new SaveOrUpdateEvent( entityName, object, this ), EventType.UPDATE );
	}

	// lock() operations ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public void lock(String entityName, Object object, LockMode lockMode) throws HibernateException {
		fireLock( new LockEvent( entityName, object, lockMode, this ) );
	}

	@Override
	public LockRequest buildLockRequest(LockOptions lockOptions) {
		return new LockRequestImpl( lockOptions );
	}

	@Override
	public void lock(Object object, LockMode lockMode) throws HibernateException {
		fireLock( new LockEvent( object, lockMode, this ) );
	}

	private void fireLock(String entityName, Object object, LockOptions options) {
		fireLock( new LockEvent( entityName, object, options, this ) );
	}

	private void fireLock(Object object, LockOptions options) {
		fireLock( new LockEvent( object, options, this ) );
	}

	private void fireLock(LockEvent event) {
		errorIfClosed();
		checkTransactionSynchStatus();
		if ( !operationContextManager.isOperationInProgress( EventType.LOCK ) ) {
			fireLockTopLevel( event );
			return;// early return
		}
		else {
			LOG.tracef(
					"%s operation cascade level: %d",
					EventType.LOCK.eventName(),
					getPersistenceContext().getCascadeLevel() );
		}
		for ( LockEventListener listener : listeners( EventType.LOCK ) ) {
			listener.onLock( event );
		}
		delayedAfterCompletion();
	}

	private void fireLockTopLevel(LockEvent event) {
		errorIfClosed();
		checkTransactionSynchStatus();
		operationContextManager.beforeOperation( EventType.LOCK, event );
		boolean success = false;
		try {
			for ( LockEventListener listener : listeners( EventType.LOCK ) ) {
				listener.onLock( event );
			}
			delayedAfterCompletion();
			success = true;
		}
		finally {
			operationContextManager.afterOperation( EventType.LOCK, event, success );
		}
	}

	// persist() operations ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public void persist(String entityName, Object object) throws HibernateException {
		firePersist( new PersistEvent( entityName, object, this ), EventType.PERSIST );
	}

	@Override
	public void persist(Object object) throws HibernateException {
		persist( null, object );
	}

	@Override
	public void persist(String entityName, Object object, Map copiedAlready) throws HibernateException {
		persist( entityName, object );
	}

	private void firePersist(PersistEvent event, EventType<PersistEventListener> actualEventType) {
		errorIfClosed();
		checkTransactionSynchStatus();
		if ( !operationContextManager.isOperationInProgress( actualEventType ) ) {
			firePersistTopLevel( event, actualEventType );
			return;  // early return
		}
		else {
			if ( TRACE_ENABLED ) {
				LOG.tracef(
						"%s operation cascade level: %d",
						actualEventType.eventName(),
						getPersistenceContext().getCascadeLevel() );
			}
		}
		for ( PersistEventListener listener : listeners( actualEventType ) ) {
			listener.onPersist( event );
		}
		delayedAfterCompletion();
	}

	private void firePersistTopLevel(PersistEvent event, EventType<PersistEventListener> actualEventType) {
		errorIfClosed();
		checkTransactionSynchStatus();
		operationContextManager.beforeOperation( actualEventType, event );
		boolean success = false;
		try {
			for ( PersistEventListener listener : listeners( actualEventType ) ) {
				listener.onPersist( event );
			}
			delayedAfterCompletion();
			success = true;
		}
		finally {
			operationContextManager.afterOperation( actualEventType, event, success );
		}
	}


	// persistOnFlush() operations ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	public void persistOnFlush(String entityName, Object object)
			throws HibernateException {
		firePersist( new PersistEvent( entityName, object, this ), EventType.PERSIST_ONFLUSH );
	}

	public void persistOnFlush(Object object) throws HibernateException {
		persist( null, object );
	}

	@Override
	public void persistOnFlush(String entityName, Object object, Map copiedAlready)
			throws HibernateException {
		persistOnFlush( entityName, object );
	}


	// merge() operations ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public Object merge(String entityName, Object object) throws HibernateException {
		return fireMerge( new MergeEvent( entityName, object, this ) );
	}

	@Override
	public Object merge(Object object) throws HibernateException {
		return merge( null, object );
	}

	@Override
	public void merge(String entityName, Object object, Map copiedAlready) throws HibernateException {
		fireMerge( new MergeEvent( entityName, object, this ) );
	}

	private Object fireMerge(MergeEvent event) {
		errorIfClosed();
		checkTransactionSynchStatus();
		if ( !operationContextManager.isOperationInProgress( EventType.MERGE ) ) {
			return fireMergeTopLevel( event ); // early return
		}
		else {
			LOG.tracef(
					"%s operation cascade level: %d",
					EventType.MERGE.eventName(),
					getPersistenceContext().getCascadeLevel() );
		}
		for ( MergeEventListener listener : listeners( EventType.MERGE ) ) {
			listener.onMerge( event );
		}
		delayedAfterCompletion();
		return event.getResult();
	}

	private Object fireMergeTopLevel(MergeEvent event) {
		errorIfClosed();
		checkTransactionSynchStatus();
		operationContextManager.beforeOperation( EventType.MERGE, event );
		boolean success = false;
		try {
			for ( MergeEventListener listener : listeners( EventType.MERGE ) ) {
				listener.onMerge( event );
			}
			delayedAfterCompletion();
			success = true;
		}
		finally {
			operationContextManager.afterOperation( EventType.MERGE, event, success );
		}
		return event.getResult();
	}


	// delete() operations ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public void delete(Object object) throws HibernateException {
		fireDelete( new DeleteEvent( object, this ) );
	}

	@Override
	public void delete(String entityName, Object object) throws HibernateException {
		fireDelete( new DeleteEvent( entityName, object, this ) );
	}

	@Override
	public void delete(String entityName, Object object, boolean isCascadeDeleteEnabled, Set transientEntities)
			throws HibernateException {
		delete( entityName, object, isCascadeDeleteEnabled );
	}

	@Override
	public void delete(String entityName, Object object, boolean isCascadeDeleteEnabled)
			throws HibernateException {
		if ( TRACE_ENABLED && persistenceContext.isRemovingOrphanBeforeUpates() ) {
			logRemoveOrphanBeforeUpdates( "before continuing", entityName, object );
		}
		fireDelete(
				new DeleteEvent(
						entityName,
						object,
						isCascadeDeleteEnabled,
						persistenceContext.isRemovingOrphanBeforeUpates(),
						this
				)
		);
		if ( TRACE_ENABLED && persistenceContext.isRemovingOrphanBeforeUpates() ) {
			logRemoveOrphanBeforeUpdates( "after continuing", entityName, object );
		}
	}

	@Override
	public void removeOrphanBeforeUpdates(String entityName, Object child) {
		// TODO: The removeOrphan concept is a temporary "hack" for HHH-6484.  This should be removed once action/task
		// ordering is improved.
		if ( TRACE_ENABLED ) {
			logRemoveOrphanBeforeUpdates( "begin", entityName, child );
		}
		persistenceContext.beginRemoveOrphanBeforeUpdates();
		try {
			fireDelete( new DeleteEvent( entityName, child, false, true, this ) );
		}
		finally {
			persistenceContext.endRemoveOrphanBeforeUpdates();
			if ( TRACE_ENABLED ) {
				logRemoveOrphanBeforeUpdates( "end", entityName, child );
			}
		}
	}

	private void logRemoveOrphanBeforeUpdates(String timing, String entityName, Object entity) {
		final EntityEntry entityEntry = persistenceContext.getEntry( entity );
		LOG.tracef(
				"%s remove orphan before updates: [%s]",
				timing,
				entityEntry == null ? entityName : MessageHelper.infoString( entityName, entityEntry.getId() )
		);
	}

	private void fireDelete(DeleteEvent event) {
		errorIfClosed();
		checkTransactionSynchStatus();
		if ( !operationContextManager.isOperationInProgress( EventType.DELETE ) ) {
			fireDeleteTopLevel( event );
			return; // early return
		}
		else {
			LOG.tracef(
					"%s operation cascade level: %d",
					EventType.DELETE.eventName(),
					getPersistenceContext().getCascadeLevel() );
		}
		for ( DeleteEventListener listener : listeners( EventType.DELETE ) ) {
			listener.onDelete( event );
		}
		delayedAfterCompletion();
	}

	private void fireDeleteTopLevel(DeleteEvent event) {
		errorIfClosed();
		checkTransactionSynchStatus();
		operationContextManager.beforeOperation( EventType.DELETE, event );
		boolean success = false;
		try {
			for ( DeleteEventListener listener : listeners( EventType.DELETE ) ) {
				listener.onDelete( event );
			}
			delayedAfterCompletion();
			success = true;
		}
		finally {
			operationContextManager.afterOperation( EventType.DELETE, event, success );
		}
	}


	// load()/get() operations ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public void load(Object object, Serializable id) throws HibernateException {
		LoadEvent event = loadEvent;
		loadEvent = null;
		if ( event == null ) {
			event = new LoadEvent( id, object, this );
		}
		else {
			event.setEntityClassName( null );
			event.setEntityId( id );
			event.setInstanceToLoad( object );
			event.setLockMode( LoadEvent.DEFAULT_LOCK_MODE );
			event.setLockScope( LoadEvent.DEFAULT_LOCK_OPTIONS.getScope() );
			event.setLockTimeout( LoadEvent.DEFAULT_LOCK_OPTIONS.getTimeOut() );
		}
		fireLoad( event, LoadEventListener.RELOAD );
		if ( loadEvent == null ) {
			event.setEntityClassName( null );
			event.setEntityId( null );
			event.setInstanceToLoad( null );
			event.setResult( null );
			loadEvent = event;
		}
	}

	@Override
	public <T> T load(Class<T> entityClass, Serializable id) throws HibernateException {
		return this.byId( entityClass ).getReference( id );
	}

	@Override
	public Object load(String entityName, Serializable id) throws HibernateException {
		return this.byId( entityName ).getReference( id );
	}

	@Override
	public <T> T get(Class<T> entityClass, Serializable id) throws HibernateException {
		return this.byId( entityClass ).load( id );
	}

	@Override
	public Object get(String entityName, Serializable id) throws HibernateException {
		return this.byId( entityName ).load( id );
	}

	/**
	 * Load the data for the object with the specified id into a newly created object.
	 * This is only called when lazily initializing a proxy.
	 * Do NOT return a proxy.
	 */
	@Override
	public Object immediateLoad(String entityName, Serializable id) throws HibernateException {
		if ( LOG.isDebugEnabled() ) {
			EntityPersister persister = getFactory().getEntityPersister( entityName );
			LOG.debugf( "Initializing proxy: %s", MessageHelper.infoString( persister, id, getFactory() ) );
		}
		LoadEvent event = loadEvent;
		loadEvent = null;
		event = recycleEventInstance( event, id, entityName );
		fireLoad( event, LoadEventListener.IMMEDIATE_LOAD );
		Object result = event.getResult();
		if ( loadEvent == null ) {
			event.setEntityClassName( null );
			event.setEntityId( null );
			event.setInstanceToLoad( null );
			event.setResult( null );
			loadEvent = event;
		}
		return result;
	}

	@Override
	public final Object internalLoad(String entityName, Serializable id, boolean eager, boolean nullable)
			throws HibernateException {
		// todo : remove
		LoadEventListener.LoadType type = nullable
				? LoadEventListener.INTERNAL_LOAD_NULLABLE
				: eager
				? LoadEventListener.INTERNAL_LOAD_EAGER
				: LoadEventListener.INTERNAL_LOAD_LAZY;

		LoadEvent event = loadEvent;
		loadEvent = null;
		event = recycleEventInstance( event, id, entityName );
		fireLoad( event, type );
		Object result = event.getResult();
		if ( !nullable ) {
			UnresolvableObjectException.throwIfNull( result, id, entityName );
		}
		if ( loadEvent == null ) {
			event.setEntityClassName( null );
			event.setEntityId( null );
			event.setInstanceToLoad( null );
			event.setResult( null );
			loadEvent = event;
		}
		return result;
	}

	/**
	 * Helper to avoid creating many new instances of LoadEvent: it's an allocation hot spot.
	 */
	private LoadEvent recycleEventInstance(final LoadEvent event, final Serializable id, final String entityName) {
		if ( event == null ) {
			return new LoadEvent( id, entityName, true, this );
		}
		else {
			event.setEntityClassName( entityName );
			event.setEntityId( id );
			event.setInstanceToLoad( null );
			event.setLockMode( LoadEvent.DEFAULT_LOCK_MODE );
			event.setLockScope( LoadEvent.DEFAULT_LOCK_OPTIONS.getScope() );
			event.setLockTimeout( LoadEvent.DEFAULT_LOCK_OPTIONS.getTimeOut() );
			return event;
		}
	}

	@Override
	public <T> T load(Class<T> entityClass, Serializable id, LockMode lockMode) throws HibernateException {
		return this.byId( entityClass ).with( new LockOptions( lockMode ) ).getReference( id );
	}

	@Override
	public <T> T load(Class<T> entityClass, Serializable id, LockOptions lockOptions) throws HibernateException {
		return this.byId( entityClass ).with( lockOptions ).getReference( id );
	}

	@Override
	public Object load(String entityName, Serializable id, LockMode lockMode) throws HibernateException {
		return this.byId( entityName ).with( new LockOptions( lockMode ) ).getReference( id );
	}

	@Override
	public Object load(String entityName, Serializable id, LockOptions lockOptions) throws HibernateException {
		return this.byId( entityName ).with( lockOptions ).getReference( id );
	}

	@Override
	public <T> T get(Class<T> entityClass, Serializable id, LockMode lockMode) throws HibernateException {
		return this.byId( entityClass ).with( new LockOptions( lockMode ) ).load( id );
	}

	@Override
	public <T> T get(Class<T> entityClass, Serializable id, LockOptions lockOptions) throws HibernateException {
		return this.byId( entityClass ).with( lockOptions ).load( id );
	}

	@Override
	public Object get(String entityName, Serializable id, LockMode lockMode) throws HibernateException {
		return this.byId( entityName ).with( new LockOptions( lockMode ) ).load( id );
	}

	@Override
	public Object get(String entityName, Serializable id, LockOptions lockOptions) throws HibernateException {
		return this.byId( entityName ).with( lockOptions ).load( id );
	}

	@Override
	public IdentifierLoadAccessImpl byId(String entityName) {
		return new IdentifierLoadAccessImpl( entityName );
	}

	@Override
	public <T> IdentifierLoadAccessImpl<T> byId(Class<T> entityClass) {
		return new IdentifierLoadAccessImpl<T>( entityClass );
	}

	@Override
	public <T> MultiIdentifierLoadAccess<T> byMultipleIds(Class<T> entityClass) {
		return new MultiIdentifierLoadAccessImpl<T>( locateEntityPersister( entityClass ) );
	}

	@Override
	public MultiIdentifierLoadAccess byMultipleIds(String entityName) {
		return new MultiIdentifierLoadAccessImpl( locateEntityPersister( entityName ) );
	}

	@Override
	public NaturalIdLoadAccess byNaturalId(String entityName) {
		return new NaturalIdLoadAccessImpl( entityName );
	}

	@Override
	public <T> NaturalIdLoadAccess<T> byNaturalId(Class<T> entityClass) {
		return new NaturalIdLoadAccessImpl<T>( entityClass );
	}

	@Override
	public SimpleNaturalIdLoadAccess bySimpleNaturalId(String entityName) {
		return new SimpleNaturalIdLoadAccessImpl( entityName );
	}

	@Override
	public <T> SimpleNaturalIdLoadAccess<T> bySimpleNaturalId(Class<T> entityClass) {
		return new SimpleNaturalIdLoadAccessImpl<T>( entityClass );
	}

	private void fireLoad(LoadEvent event, LoadType loadType) {
		errorIfClosed();
		checkTransactionSynchStatus();
		for ( LoadEventListener listener : listeners( EventType.LOAD ) ) {
			listener.onLoad( event, loadType );
		}
		delayedAfterCompletion();
	}

	private void fireResolveNaturalId(ResolveNaturalIdEvent event) {
		errorIfClosed();
		checkTransactionSynchStatus();
		for ( ResolveNaturalIdEventListener listener : listeners( EventType.RESOLVE_NATURAL_ID ) ) {
			listener.onResolveNaturalId( event );
		}
		delayedAfterCompletion();
	}


	// refresh() operations ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public void refresh(Object object) throws HibernateException {
		refresh( null, object );
	}

	@Override
	public void refresh(String entityName, Object object) throws HibernateException {
		fireRefresh( new RefreshEvent( entityName, object, this ) );
	}

	@Override
	public void refresh(Object object, LockMode lockMode) throws HibernateException {
		fireRefresh( new RefreshEvent( object, lockMode, this ) );
	}

	@Override
	public void refresh(Object object, LockOptions lockOptions) throws HibernateException {
		refresh( null, object, lockOptions );
	}

	@Override
	public void refresh(String entityName, Object object, LockOptions lockOptions) throws HibernateException {
		fireRefresh( new RefreshEvent( entityName, object, lockOptions, this ) );
	}

	@Override
	public void refresh(String entityName, Object object, Map refreshedAlready) throws HibernateException {
		refresh( entityName, object );
	}

	private void fireRefresh(RefreshEvent event) {
		errorIfClosed();
		checkTransactionSynchStatus();
		if ( !operationContextManager.isOperationInProgress( EventType.REFRESH ) ) {
			fireRefreshTopLevel( event );
			return; // early return
		}
		else {
			LOG.tracef(
					"%s operation cascade level: %d",
					EventType.REFRESH.eventName(),
					getPersistenceContext().getCascadeLevel() );
		}
		for ( RefreshEventListener listener : listeners( EventType.REFRESH ) ) {
			listener.onRefresh( event );
		}
		delayedAfterCompletion();
	}

	private void fireRefreshTopLevel(RefreshEvent event) {
		errorIfClosed();
		checkTransactionSynchStatus();
		operationContextManager.beforeOperation( EventType.REFRESH, event );
		boolean success = false;
		try {
			for ( RefreshEventListener listener : listeners( EventType.REFRESH ) ) {
				listener.onRefresh( event );
			}
			delayedAfterCompletion();
			delayedAfterCompletion();
			success = true;
		}
		finally {
			operationContextManager.afterOperation( EventType.REFRESH, event, success );
		}
	}


	// replicate() operations ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public void replicate(Object obj, ReplicationMode replicationMode) throws HibernateException {
		fireReplicate( replicationMode, new ReplicateEvent( obj, replicationMode, this ) );
	}

	@Override
	public void replicate(String entityName, Object obj, ReplicationMode replicationMode)
			throws HibernateException {
		fireReplicate( replicationMode, new ReplicateEvent( entityName, obj, replicationMode, this ) );
	}

	private void fireReplicate(ReplicationMode replicationMode, ReplicateEvent event) {
		errorIfClosed();
		checkTransactionSynchStatus();
		if ( !operationContextManager.isOperationInProgress( EventType.REPLICATE ) ) {
			fireTopLevelReplicate( replicationMode, event );
			return; // early return
		}
		else {
			LOG.tracef(
					"%s operation cascade level: %d",
					EventType.REPLICATE.eventName(),
					getPersistenceContext().getCascadeLevel() );
		}
		for ( ReplicateEventListener listener : listeners( EventType.REPLICATE ) ) {
			listener.onReplicate( event );
		}
		delayedAfterCompletion();
	}

	private void fireTopLevelReplicate(ReplicationMode replicationMode, ReplicateEvent event) {
		errorIfClosed();
		checkTransactionSynchStatus();
		operationContextManager.beforeOperation( EventType.REPLICATE, event );
		boolean success = false;
		try {
			for ( ReplicateEventListener listener : listeners( EventType.REPLICATE ) ) {
				listener.onReplicate( event );
			}
			delayedAfterCompletion();
			success = true;
		}
		finally {
			operationContextManager.afterOperation( EventType.REPLICATE, event, success );
		}
	}


	// evict() operations ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	/**
	 * remove any hard references to the entity that are held by the infrastructure
	 * (references held by application or other persistent instances are okay)
	 */
	@Override
	public void evict(Object object) throws HibernateException {
		fireEvict( new EvictEvent( object, this ) );
	}

	private void fireEvict(EvictEvent event) {
		errorIfClosed();
		checkTransactionSynchStatus();
		for ( EvictEventListener listener : listeners( EventType.EVICT ) ) {
			listener.onEvict( event );
		}
		delayedAfterCompletion();
	}

	/**
	 * detect in-memory changes, determine if the changes are to tables
	 * named in the query and, if so, complete execution the flush
	 */
	protected boolean autoFlushIfRequired(Set querySpaces) throws HibernateException {
		errorIfClosed();
		if ( !isTransactionInProgress() ) {
			// do not auto-flush while outside a transaction
			return false;
		}
		// autoFlush is always a top-level operation.
		AutoFlushEvent event = new AutoFlushEvent( querySpaces, this );
		//operationContextManager.beforeOperation( EventType.AUTO_FLUSH, event );
		//boolean success = false;
		//try {
			for ( AutoFlushEventListener listener : listeners( EventType.AUTO_FLUSH ) ) {
				listener.onAutoFlush( event );
			}
		//	success = true;
			return event.isFlushRequired();
		//}
		//finally {
		//	operationContextManager.afterOperation( EventType.AUTO_FLUSH, event, success );
		//}
	}

	@Override
	public boolean isDirty() throws HibernateException {
		errorIfClosed();
		checkTransactionSynchStatus();
		LOG.debug( "Checking session dirtiness" );
		if ( actionQueue.areInsertionsOrDeletionsQueued() ) {
			LOG.debug( "Session dirty (scheduled updates and insertions)" );
			return true;
		}
		DirtyCheckEvent event = new DirtyCheckEvent( this );
		for ( DirtyCheckEventListener listener : listeners( EventType.DIRTY_CHECK ) ) {
			listener.onDirtyCheck( event );
		}
		delayedAfterCompletion();
		return event.isDirty();
	}

	@Override
	public void flush() throws HibernateException {
		errorIfClosed();
		checkTransactionSynchStatus();
		if ( persistenceContext.getCascadeLevel() > 0 ) {
			throw new HibernateException( "Flush during cascade is dangerous" );
		}
		// flush() is always a top-level operation.
		FlushEvent flushEvent = new FlushEvent( this );
		//operationContextManager.beforeOperation( EventType.FLUSH, flushEvent );
		//boolean success = false;
		//try {
			for ( FlushEventListener listener : listeners( EventType.FLUSH ) ) {
				listener.onFlush( flushEvent );
			}
			delayedAfterCompletion();
		//	success = true;
		//}
		//finally {
		//	operationContextManager.afterOperation( EventType.FLUSH, flushEvent, success );
		//}
	}

	@Override
	public void forceFlush(EntityEntry entityEntry) throws HibernateException {
		errorIfClosed();
		if ( LOG.isDebugEnabled() ) {
			LOG.debugf(
					"Flushing to force deletion of re-saved object: %s",
					MessageHelper.infoString( entityEntry.getPersister(), entityEntry.getId(), getFactory() )
			);
		}

		if ( persistenceContext.getCascadeLevel() > 0 ) {
			throw new ObjectDeletedException(
					"deleted object would be re-saved by cascade (remove deleted object from associations)",
					entityEntry.getId(),
					entityEntry.getPersister().getEntityName()
			);
		}

		flush();
	}

	@Override
	public List list(String query, QueryParameters queryParameters) throws HibernateException {
		errorIfClosed();
		checkTransactionSynchStatus();
		queryParameters.validateParameters();

		HQLQueryPlan plan = queryParameters.getQueryPlan();
		if ( plan == null ) {
			plan = getHQLQueryPlan( query, false );
		}

		autoFlushIfRequired( plan.getQuerySpaces() );

		List results = Collections.EMPTY_LIST;
		boolean success = false;

		dontFlushFromFind++;   //stops flush being called multiple times if this method is recursively called
		try {
			results = plan.performList( queryParameters, this );
			success = true;
		}
		finally {
			dontFlushFromFind--;
			afterOperation( success );
			delayedAfterCompletion();
		}
		return results;
	}

	@Override
	public int executeUpdate(String query, QueryParameters queryParameters) throws HibernateException {
		errorIfClosed();
		checkTransactionSynchStatus();
		queryParameters.validateParameters();
		HQLQueryPlan plan = getHQLQueryPlan( query, false );
		autoFlushIfRequired( plan.getQuerySpaces() );

		boolean success = false;
		int result = 0;
		try {
			result = plan.performExecuteUpdate( queryParameters, this );
			success = true;
		}
		finally {
			afterOperation( success );
			delayedAfterCompletion();
		}
		return result;
	}

	@Override
	public int executeNativeUpdate(
			NativeSQLQuerySpecification nativeQuerySpecification,
			QueryParameters queryParameters) throws HibernateException {
		errorIfClosed();
		checkTransactionSynchStatus();
		queryParameters.validateParameters();
		NativeSQLQueryPlan plan = getNativeSQLQueryPlan( nativeQuerySpecification );


		autoFlushIfRequired( plan.getCustomQuery().getQuerySpaces() );

		boolean success = false;
		int result = 0;
		try {
			result = plan.performExecuteUpdate( queryParameters, this );
			success = true;
		}
		finally {
			afterOperation( success );
			delayedAfterCompletion();
		}
		return result;
	}

	@Override
	public Iterator iterate(String query, QueryParameters queryParameters) throws HibernateException {
		errorIfClosed();
		checkTransactionSynchStatus();
		queryParameters.validateParameters();

		HQLQueryPlan plan = queryParameters.getQueryPlan();
		if ( plan == null ) {
			plan = getHQLQueryPlan( query, true );
		}

		autoFlushIfRequired( plan.getQuerySpaces() );

		dontFlushFromFind++; //stops flush being called multiple times if this method is recursively called
		try {
			return plan.performIterate( queryParameters, this );
		}
		finally {
			delayedAfterCompletion();
			dontFlushFromFind--;
		}
	}

	@Override
	public ScrollableResults scroll(String query, QueryParameters queryParameters) throws HibernateException {
		errorIfClosed();
		checkTransactionSynchStatus();
		
		HQLQueryPlan plan = queryParameters.getQueryPlan();
		if ( plan == null ) {
			plan = getHQLQueryPlan( query, false );
		}
		
		autoFlushIfRequired( plan.getQuerySpaces() );

		dontFlushFromFind++;
		try {
			return plan.performScroll( queryParameters, this );
		}
		finally {
			delayedAfterCompletion();
			dontFlushFromFind--;
		}
	}

	@Override
	public Query createFilter(Object collection, String queryString) {
		errorIfClosed();
		checkTransactionSynchStatus();
		CollectionFilterImpl filter = new CollectionFilterImpl(
				queryString,
				collection,
				this,
				getFilterQueryPlan( collection, queryString, null, false ).getParameterMetadata()
		);
		filter.setComment( queryString );
		delayedAfterCompletion();
		return filter;
	}

	@Override
	public Query getNamedQuery(String queryName) throws MappingException {
		errorIfClosed();
		checkTransactionSynchStatus();
		Query query = super.getNamedQuery( queryName );
		delayedAfterCompletion();
		return query;
	}

	@Override
	public Object instantiate(String entityName, Serializable id) throws HibernateException {
		return instantiate( factory.getEntityPersister( entityName ), id );
	}

	/**
	 * give the interceptor an opportunity to override the default instantiation
	 */
	@Override
	public Object instantiate(EntityPersister persister, Serializable id) throws HibernateException {
		errorIfClosed();
		checkTransactionSynchStatus();
		Object result = interceptor.instantiate(
				persister.getEntityName(),
				persister.getEntityMetamodel().getEntityMode(),
				id
		);
		if ( result == null ) {
			result = persister.instantiate( id, this );
		}
		delayedAfterCompletion();
		return result;
	}

	@Override
	public void setFlushMode(FlushMode flushMode) {
		errorIfClosed();
		checkTransactionSynchStatus();
		LOG.tracev( "Setting flush mode to: {0}", flushMode );
		this.flushMode = flushMode;
	}

	@Override
	public FlushMode getFlushMode() {
		checkTransactionSynchStatus();
		return flushMode;
	}

	@Override
	public CacheMode getCacheMode() {
		checkTransactionSynchStatus();
		return cacheMode;
	}

	@Override
	public void setCacheMode(CacheMode cacheMode) {
		errorIfClosed();
		checkTransactionSynchStatus();
		LOG.tracev( "Setting cache mode to: {0}", cacheMode );
		this.cacheMode = cacheMode;
	}

	@Override
	public Transaction beginTransaction() throws HibernateException {
		errorIfClosed();
		Transaction result = getTransaction();
		// begin on already started transaction is noop, therefore, don't update the timestamp
		if (result.getStatus() != TransactionStatus.ACTIVE) {
			timestamp = factory.getSettings().getRegionFactory().nextTimestamp();
		}
		result.begin();
		return result;
	}

	@Override
	public EntityPersister getEntityPersister(final String entityName, final Object object) {
		errorIfClosed();
		if ( entityName == null ) {
			return factory.getEntityPersister( guessEntityName( object ) );
		}
		else {
			// try block is a hack around fact that currently tuplizers are not
			// given the opportunity to resolve a subclass entity name.  this
			// allows the (we assume custom) interceptor the ability to
			// influence this decision if we were not able to based on the
			// given entityName
			try {
				return factory.getEntityPersister( entityName ).getSubclassEntityPersister( object, getFactory() );
			}
			catch (HibernateException e) {
				try {
					return getEntityPersister( null, object );
				}
				catch (HibernateException e2) {
					throw e;
				}
			}
		}
	}

	// not for internal use:
	@Override
	public Serializable getIdentifier(Object object) throws HibernateException {
		errorIfClosed();
		checkTransactionSynchStatus();
		if ( object instanceof HibernateProxy ) {
			LazyInitializer li = ( (HibernateProxy) object ).getHibernateLazyInitializer();
			if ( li.getSession() != this ) {
				throw new TransientObjectException( "The proxy was not associated with this session" );
			}
			return li.getIdentifier();
		}
		else {
			EntityEntry entry = persistenceContext.getEntry( object );
			if ( entry == null ) {
				throw new TransientObjectException( "The instance was not associated with this session" );
			}
			return entry.getId();
		}
	}

	/**
	 * Get the id value for an object that is actually associated with the session. This
	 * is a bit stricter than getEntityIdentifierIfNotUnsaved().
	 */
	@Override
	public Serializable getContextEntityIdentifier(Object object) {
		errorIfClosed();
		if ( object instanceof HibernateProxy ) {
			return getProxyIdentifier( object );
		}
		else {
			EntityEntry entry = persistenceContext.getEntry( object );
			return entry != null ? entry.getId() : null;
		}
	}

	private Serializable getProxyIdentifier(Object proxy) {
		return ( (HibernateProxy) proxy ).getHibernateLazyInitializer().getIdentifier();
	}

	private FilterQueryPlan getFilterQueryPlan(
			Object collection,
			String filter,
			QueryParameters parameters,
			boolean shallow) throws HibernateException {
		if ( collection == null ) {
			throw new NullPointerException( "null collection passed to filter" );
		}

		CollectionEntry entry = persistenceContext.getCollectionEntryOrNull( collection );
		final CollectionPersister roleBeforeFlush = ( entry == null ) ? null : entry.getLoadedPersister();

		FilterQueryPlan plan = null;
		if ( roleBeforeFlush == null ) {
			// if it was previously unreferenced, we need to flush in order to
			// get its state into the database in order to execute query
			flush();
			entry = persistenceContext.getCollectionEntryOrNull( collection );
			CollectionPersister roleAfterFlush = ( entry == null ) ? null : entry.getLoadedPersister();
			if ( roleAfterFlush == null ) {
				throw new QueryException( "The collection was unreferenced" );
			}
			plan = factory.getQueryPlanCache().getFilterQueryPlan(
					filter,
					roleAfterFlush.getRole(),
					shallow,
					getLoadQueryInfluencers().getEnabledFilters()
			);
		}
		else {
			// otherwise, we only need to flush if there are in-memory changes
			// to the queried tables
			plan = factory.getQueryPlanCache().getFilterQueryPlan(
					filter,
					roleBeforeFlush.getRole(),
					shallow,
					getLoadQueryInfluencers().getEnabledFilters()
			);
			if ( autoFlushIfRequired( plan.getQuerySpaces() ) ) {
				// might need to run a different filter entirely after the flush
				// because the collection role may have changed
				entry = persistenceContext.getCollectionEntryOrNull( collection );
				CollectionPersister roleAfterFlush = ( entry == null ) ? null : entry.getLoadedPersister();
				if ( roleBeforeFlush != roleAfterFlush ) {
					if ( roleAfterFlush == null ) {
						throw new QueryException( "The collection was dereferenced" );
					}
					plan = factory.getQueryPlanCache().getFilterQueryPlan(
							filter,
							roleAfterFlush.getRole(),
							shallow,
							getLoadQueryInfluencers().getEnabledFilters()
					);
				}
			}
		}

		if ( parameters != null ) {
			parameters.getPositionalParameterValues()[0] = entry.getLoadedKey();
			parameters.getPositionalParameterTypes()[0] = entry.getLoadedPersister().getKeyType();
		}

		return plan;
	}

	@Override
	public List listFilter(Object collection, String filter, QueryParameters queryParameters)
			throws HibernateException {
		errorIfClosed();
		checkTransactionSynchStatus();
		FilterQueryPlan plan = getFilterQueryPlan( collection, filter, queryParameters, false );
		List results = Collections.EMPTY_LIST;

		boolean success = false;
		dontFlushFromFind++;   //stops flush being called multiple times if this method is recursively called
		try {
			results = plan.performList( queryParameters, this );
			success = true;
		}
		finally {
			dontFlushFromFind--;
			afterOperation( success );
			delayedAfterCompletion();
		}
		return results;
	}

	@Override
	public Iterator iterateFilter(Object collection, String filter, QueryParameters queryParameters)
			throws HibernateException {
		errorIfClosed();
		checkTransactionSynchStatus();
		FilterQueryPlan plan = getFilterQueryPlan( collection, filter, queryParameters, true );
		Iterator itr = plan.performIterate( queryParameters, this );
		delayedAfterCompletion();
		return itr;
	}

	@Override
	public Criteria createCriteria(Class persistentClass, String alias) {
		errorIfClosed();
		checkTransactionSynchStatus();
		return new CriteriaImpl( persistentClass.getName(), alias, this );
	}

	@Override
	public Criteria createCriteria(String entityName, String alias) {
		errorIfClosed();
		checkTransactionSynchStatus();
		return new CriteriaImpl( entityName, alias, this );
	}

	@Override
	public Criteria createCriteria(Class persistentClass) {
		errorIfClosed();
		checkTransactionSynchStatus();
		return new CriteriaImpl( persistentClass.getName(), this );
	}

	@Override
	public Criteria createCriteria(String entityName) {
		errorIfClosed();
		checkTransactionSynchStatus();
		return new CriteriaImpl( entityName, this );
	}

	@Override
	public ScrollableResults scroll(Criteria criteria, ScrollMode scrollMode) {
		// TODO: Is this guaranteed to always be CriteriaImpl?
		CriteriaImpl criteriaImpl = (CriteriaImpl) criteria;

		errorIfClosed();
		checkTransactionSynchStatus();
		String entityName = criteriaImpl.getEntityOrClassName();
		CriteriaLoader loader = new CriteriaLoader(
				getOuterJoinLoadable( entityName ),
				factory,
				criteriaImpl,
				entityName,
				getLoadQueryInfluencers()
		);
		autoFlushIfRequired( loader.getQuerySpaces() );
		dontFlushFromFind++;
		try {
			return loader.scroll( this, scrollMode );
		}
		finally {
			delayedAfterCompletion();
			dontFlushFromFind--;
		}
	}

	@Override
	public List list(Criteria criteria) throws HibernateException {
		// TODO: Is this guaranteed to always be CriteriaImpl?
		CriteriaImpl criteriaImpl = (CriteriaImpl) criteria;

		final NaturalIdLoadAccess naturalIdLoadAccess = this.tryNaturalIdLoadAccess( criteriaImpl );
		if ( naturalIdLoadAccess != null ) {
			// EARLY EXIT!
			return Arrays.asList( naturalIdLoadAccess.load() );
		}

		errorIfClosed();
		checkTransactionSynchStatus();
		String[] implementors = factory.getImplementors( criteriaImpl.getEntityOrClassName() );
		int size = implementors.length;

		CriteriaLoader[] loaders = new CriteriaLoader[size];
		Set spaces = new HashSet();
		for ( int i = 0; i < size; i++ ) {

			loaders[i] = new CriteriaLoader(
					getOuterJoinLoadable( implementors[i] ),
					factory,
					criteriaImpl,
					implementors[i],
					getLoadQueryInfluencers()
			);

			spaces.addAll( loaders[i].getQuerySpaces() );

		}

		autoFlushIfRequired( spaces );

		List results = Collections.EMPTY_LIST;
		dontFlushFromFind++;
		boolean success = false;
		try {
			for ( int i = 0; i < size; i++ ) {
				final List currentResults = loaders[i].list( this );
				currentResults.addAll( results );
				results = currentResults;
			}
			success = true;
		}
		finally {
			dontFlushFromFind--;
			afterOperation( success );
			delayedAfterCompletion();
		}

		return results;
	}

	/**
	 * Checks to see if the CriteriaImpl is a naturalId lookup that can be done via
	 * NaturalIdLoadAccess
	 *
	 * @param criteria The criteria to check as a complete natural identifier lookup.
	 *
	 * @return A fully configured NaturalIdLoadAccess or null, if null is returned the standard CriteriaImpl execution
	 * should be performed
	 */
	private NaturalIdLoadAccess tryNaturalIdLoadAccess(CriteriaImpl criteria) {
		// See if the criteria lookup is by naturalId
		if ( !criteria.isLookupByNaturalKey() ) {
			return null;
		}

		final String entityName = criteria.getEntityOrClassName();
		final EntityPersister entityPersister = factory.getEntityPersister( entityName );

		// Verify the entity actually has a natural id, needed for legacy support as NaturalIdentifier criteria
		// queries did no natural id validation
		if ( !entityPersister.hasNaturalIdentifier() ) {
			return null;
		}

		// Since isLookupByNaturalKey is true there can be only one CriterionEntry and getCriterion() will
		// return an instanceof NaturalIdentifier
		final CriterionEntry criterionEntry = (CriterionEntry) criteria.iterateExpressionEntries().next();
		final NaturalIdentifier naturalIdentifier = (NaturalIdentifier) criterionEntry.getCriterion();

		final Map<String, Object> naturalIdValues = naturalIdentifier.getNaturalIdValues();
		final int[] naturalIdentifierProperties = entityPersister.getNaturalIdentifierProperties();

		// Verify the NaturalIdentifier criterion includes all naturalId properties, first check that the property counts match
		if ( naturalIdentifierProperties.length != naturalIdValues.size() ) {
			return null;
		}

		final String[] propertyNames = entityPersister.getPropertyNames();
		final NaturalIdLoadAccess naturalIdLoader = this.byNaturalId( entityName );

		// Build NaturalIdLoadAccess and in the process verify all naturalId properties were specified
		for ( int naturalIdentifierProperty : naturalIdentifierProperties ) {
			final String naturalIdProperty = propertyNames[naturalIdentifierProperty];
			final Object naturalIdValue = naturalIdValues.get( naturalIdProperty );

			if ( naturalIdValue == null ) {
				// A NaturalId property is missing from the critera query, can't use NaturalIdLoadAccess
				return null;
			}

			naturalIdLoader.using( naturalIdProperty, naturalIdValue );
		}

		// Critera query contains a valid naturalId, use the new API
		LOG.warn(
				"Session.byNaturalId(" + entityName
						+ ") should be used for naturalId queries instead of Restrictions.naturalId() from a Criteria"
		);

		return naturalIdLoader;
	}

	private OuterJoinLoadable getOuterJoinLoadable(String entityName) throws MappingException {
		EntityPersister persister = factory.getEntityPersister( entityName );
		if ( !( persister instanceof OuterJoinLoadable ) ) {
			throw new MappingException( "class persister is not OuterJoinLoadable: " + entityName );
		}
		return (OuterJoinLoadable) persister;
	}

	@Override
	public boolean contains(Object object) {
		errorIfClosed();
		checkTransactionSynchStatus();
		if ( object instanceof HibernateProxy ) {
			//do not use proxiesByKey, since not all
			//proxies that point to this session's
			//instances are in that collection!
			LazyInitializer li = ( (HibernateProxy) object ).getHibernateLazyInitializer();
			if ( li.isUninitialized() ) {
				//if it is an uninitialized proxy, pointing
				//with this session, then when it is accessed,
				//the underlying instance will be "contained"
				return li.getSession() == this;
			}
			else {
				//if it is initialized, see if the underlying
				//instance is contained, since we need to
				//account for the fact that it might have been
				//evicted
				object = li.getImplementation();
			}
		}
		// A session is considered to contain an entity only if the entity has
		// an entry in the session's persistence context and the entry reports
		// that the entity has not been removed
		EntityEntry entry = persistenceContext.getEntry( object );
		delayedAfterCompletion();
		return entry != null && entry.getStatus() != Status.DELETED && entry.getStatus() != Status.GONE;
	}

	@Override
	public Query createQuery(String queryString) {
		errorIfClosed();
		checkTransactionSynchStatus();
		return super.createQuery( queryString );
	}

	@Override
	public SQLQuery createSQLQuery(String sql) {
		errorIfClosed();
		checkTransactionSynchStatus();
		return super.createSQLQuery( sql );
	}

	@Override
	public ProcedureCall createStoredProcedureCall(String procedureName) {
		errorIfClosed();
		checkTransactionSynchStatus();
		return super.createStoredProcedureCall( procedureName );
	}

	@Override
	public ProcedureCall createStoredProcedureCall(String procedureName, String... resultSetMappings) {
		errorIfClosed();
		checkTransactionSynchStatus();
		return super.createStoredProcedureCall( procedureName, resultSetMappings );
	}

	@Override
	public ProcedureCall createStoredProcedureCall(String procedureName, Class... resultClasses) {
		errorIfClosed();
		checkTransactionSynchStatus();
		return super.createStoredProcedureCall( procedureName, resultClasses );
	}

	@Override
	public ScrollableResults scrollCustomQuery(CustomQuery customQuery, QueryParameters queryParameters)
			throws HibernateException {
		errorIfClosed();
		checkTransactionSynchStatus();

		if ( LOG.isTraceEnabled() ) {
			LOG.tracev( "Scroll SQL query: {0}", customQuery.getSQL() );
		}

		CustomLoader loader = new CustomLoader( customQuery, getFactory() );

		autoFlushIfRequired( loader.getQuerySpaces() );

		dontFlushFromFind++; //stops flush being called multiple times if this method is recursively called
		try {
			return loader.scroll( queryParameters, this );
		}
		finally {
			delayedAfterCompletion();
			dontFlushFromFind--;
		}
	}

	// basically just an adapted copy of find(CriteriaImpl)
	@Override
	public List listCustomQuery(CustomQuery customQuery, QueryParameters queryParameters)
			throws HibernateException {
		errorIfClosed();
		checkTransactionSynchStatus();

		if ( LOG.isTraceEnabled() ) {
			LOG.tracev( "SQL query: {0}", customQuery.getSQL() );
		}

		CustomLoader loader = new CustomLoader( customQuery, getFactory() );

		autoFlushIfRequired( loader.getQuerySpaces() );

		dontFlushFromFind++;
		boolean success = false;
		try {
			List results = loader.list( this, queryParameters );
			success = true;
			return results;
		}
		finally {
			dontFlushFromFind--;
			delayedAfterCompletion();
			afterOperation( success );
		}
	}

	@Override
	public SessionFactoryImplementor getSessionFactory() {
		checkTransactionSynchStatus();
		return factory;
	}

	@Override
	public void initializeCollection(PersistentCollection collection, boolean writing)
			throws HibernateException {
		errorIfClosed();
		checkTransactionSynchStatus();
		InitializeCollectionEvent event = new InitializeCollectionEvent( collection, this );
		for ( InitializeCollectionEventListener listener : listeners( EventType.INIT_COLLECTION ) ) {
			listener.onInitializeCollection( event );
		}
		delayedAfterCompletion();
	}

	@Override
	public String bestGuessEntityName(Object object) {
		if ( object instanceof HibernateProxy ) {
			LazyInitializer initializer = ( (HibernateProxy) object ).getHibernateLazyInitializer();
			// it is possible for this method to be called during flush processing,
			// so make certain that we do not accidentally initialize an uninitialized proxy
			if ( initializer.isUninitialized() ) {
				return initializer.getEntityName();
			}
			object = initializer.getImplementation();
		}
		EntityEntry entry = persistenceContext.getEntry( object );
		if ( entry == null ) {
			return guessEntityName( object );
		}
		else {
			return entry.getPersister().getEntityName();
		}
	}

	@Override
	public String getEntityName(Object object) {
		errorIfClosed();
		checkTransactionSynchStatus();
		if ( object instanceof HibernateProxy ) {
			if ( !persistenceContext.containsProxy( object ) ) {
				throw new TransientObjectException( "proxy was not associated with the session" );
			}
			object = ( (HibernateProxy) object ).getHibernateLazyInitializer().getImplementation();
		}

		EntityEntry entry = persistenceContext.getEntry( object );
		if ( entry == null ) {
			throwTransientObjectException( object );
		}
		return entry.getPersister().getEntityName();
	}

	private void throwTransientObjectException(Object object) throws HibernateException {
		throw new TransientObjectException(
				"object references an unsaved transient instance - save the transient instance before flushing: " +
						guessEntityName( object )
		);
	}

	@Override
	public String guessEntityName(Object object) throws HibernateException {
		errorIfClosed();
		return entityNameResolver.resolveEntityName( object );
	}

	@Override
	public void cancelQuery() throws HibernateException {
		errorIfClosed();
		this.jdbcCoordinator.cancelLastQuery();
	}

	@Override
	public Interceptor getInterceptor() {
		checkTransactionSynchStatus();
		return interceptor;
	}

	@Override
	public int getDontFlushFromFind() {
		return dontFlushFromFind;
	}

	@Override
	public String toString() {
		StringBuilder buf = new StringBuilder( 500 )
				.append( "SessionImpl(" );
		if ( !isClosed() ) {
			buf.append( persistenceContext )
					.append( ";" )
					.append( actionQueue );
		}
		else {
			buf.append( "<closed>" );
		}
		return buf.append( ')' ).toString();
	}

	@Override
	public ActionQueue getActionQueue() {
		errorIfClosed();
		checkTransactionSynchStatus();
		return actionQueue;
	}

	@Override
	public PersistenceContext getPersistenceContext() {
		errorIfClosed();
		checkTransactionSynchStatus();
		return persistenceContext;
	}

	@Override
	public SessionStatistics getStatistics() {
		checkTransactionSynchStatus();
		return new SessionStatisticsImpl( this );
	}

	@Override
	public boolean isEventSource() {
		checkTransactionSynchStatus();
		return true;
	}

	@Override
	public boolean isDefaultReadOnly() {
		return persistenceContext.isDefaultReadOnly();
	}

	@Override
	public void setDefaultReadOnly(boolean defaultReadOnly) {
		persistenceContext.setDefaultReadOnly( defaultReadOnly );
	}

	@Override
	public boolean isReadOnly(Object entityOrProxy) {
		errorIfClosed();
		checkTransactionSynchStatus();
		return persistenceContext.isReadOnly( entityOrProxy );
	}

	@Override
	public void setReadOnly(Object entity, boolean readOnly) {
		errorIfClosed();
		checkTransactionSynchStatus();
		persistenceContext.setReadOnly( entity, readOnly );
	}

	@Override
	public void doWork(final Work work) throws HibernateException {
		WorkExecutorVisitable<Void> realWork = new WorkExecutorVisitable<Void>() {
			@Override
			public Void accept(WorkExecutor<Void> workExecutor, Connection connection) throws SQLException {
				workExecutor.executeWork( work, connection );
				return null;
			}
		};
		doWork( realWork );
	}

	@Override
	public <T> T doReturningWork(final ReturningWork<T> work) throws HibernateException {
		WorkExecutorVisitable<T> realWork = new WorkExecutorVisitable<T>() {
			@Override
			public T accept(WorkExecutor<T> workExecutor, Connection connection) throws SQLException {
				return workExecutor.executeReturningWork( work, connection );
			}
		};
		return doWork( realWork );
	}

	private <T> T doWork(WorkExecutorVisitable<T> work) throws HibernateException {
		return this.jdbcCoordinator.coordinateWork( work );
	}

	@Override
	public void afterScrollOperation() {
		// nothing to do in a stateful session
	}

	@Override
	public TransactionCoordinator getTransactionCoordinator() {
		errorIfClosed();
		return transactionCoordinator;
	}

	@Override
	public JdbcCoordinator getJdbcCoordinator() {
		return this.jdbcCoordinator;
	}

	@Override
	public LoadQueryInfluencers getLoadQueryInfluencers() {
		return loadQueryInfluencers;
	}

	// filter support ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public Filter getEnabledFilter(String filterName) {
		checkTransactionSynchStatus();
		return loadQueryInfluencers.getEnabledFilter( filterName );
	}

	@Override
	public Filter enableFilter(String filterName) {
		errorIfClosed();
		checkTransactionSynchStatus();
		return loadQueryInfluencers.enableFilter( filterName );
	}

	@Override
	public void disableFilter(String filterName) {
		errorIfClosed();
		checkTransactionSynchStatus();
		loadQueryInfluencers.disableFilter( filterName );
	}


	// fetch profile support ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public boolean isFetchProfileEnabled(String name) throws UnknownProfileException {
		return loadQueryInfluencers.isFetchProfileEnabled( name );
	}

	@Override
	public void enableFetchProfile(String name) throws UnknownProfileException {
		loadQueryInfluencers.enableFetchProfile( name );
	}

	@Override
	public void disableFetchProfile(String name) throws UnknownProfileException {
		loadQueryInfluencers.disableFetchProfile( name );
	}

	private void checkTransactionSynchStatus() {
		pulseTransactionCoordinator();
		delayedAfterCompletion();
	}

	private void pulseTransactionCoordinator() {
		if ( !isClosed() ) {
			transactionCoordinator.pulse();
		}
	}

	/**
	 * Used by JDK serialization...
	 *
	 * @param ois The input stream from which we are being read...
	 *
	 * @throws IOException Indicates a general IO stream exception
	 * @throws ClassNotFoundException Indicates a class resolution issue
	 */
	private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException, SQLException {
		LOG.trace( "Deserializing session" );

		ois.defaultReadObject();

		entityNameResolver = new CoordinatingEntityNameResolver();
		operationContextManager = new OperationContextManager();

		connectionReleaseMode = ConnectionReleaseMode.parse( (String) ois.readObject() );
		autoClear = ois.readBoolean();
		autoJoinTransactions = ois.readBoolean();
		flushMode = FlushMode.valueOf( (String) ois.readObject() );
		cacheMode = CacheMode.valueOf( (String) ois.readObject() );
		flushBeforeCompletionEnabled = ois.readBoolean();
		autoCloseSessionEnabled = ois.readBoolean();
		interceptor = (Interceptor) ois.readObject();

		factory = SessionFactoryImpl.deserialize( ois );
		this.jdbcSessionContext = new JdbcSessionContextImpl( factory, statementInspector );
		sessionOwner = (SessionOwner) ois.readObject();

		initializeFromSessionOwner( sessionOwner );

		jdbcCoordinator = JdbcCoordinatorImpl.deserialize( ois, this );

		this.transactionCoordinator = getTransactionCoordinatorBuilder().buildTransactionCoordinator(
				jdbcCoordinator,
				this
		);

		persistenceContext = StatefulPersistenceContext.deserialize( ois, this );
		actionQueue = ActionQueue.deserialize( ois, this );

		loadQueryInfluencers = (LoadQueryInfluencers) ois.readObject();

		// LoadQueryInfluencers.getEnabledFilters() tries to validate each enabled
		// filter, which will fail when called before FilterImpl.afterDeserialize( factory );
		// Instead lookup the filter by name and then call FilterImpl.afterDeserialize( factory ).
		for ( String filterName : loadQueryInfluencers.getEnabledFilterNames() ) {
			( (FilterImpl) loadQueryInfluencers.getEnabledFilter( filterName ) ).afterDeserialize( factory );
		}
	}

	/**
	 * Used by JDK serialization...
	 *
	 * @param oos The output stream to which we are being written...
	 *
	 * @throws IOException Indicates a general IO stream exception
	 */
	private void writeObject(ObjectOutputStream oos) throws IOException {
		if ( !jdbcCoordinator.isReadyForSerialization() ) {
			throw new IllegalStateException( "Cannot serialize a session while connected" );
		}

		LOG.trace( "Serializing session" );

		oos.defaultWriteObject();

		oos.writeObject( connectionReleaseMode.toString() );
		oos.writeBoolean( autoClear );
		oos.writeBoolean( autoJoinTransactions );
		oos.writeObject( flushMode.toString() );
		oos.writeObject( cacheMode.name() );
		oos.writeBoolean( flushBeforeCompletionEnabled );
		oos.writeBoolean( autoCloseSessionEnabled );
		// we need to writeObject() on this since interceptor is user defined
		oos.writeObject( interceptor );

		factory.serialize( oos );
		oos.writeObject( sessionOwner );

		jdbcCoordinator.serialize( oos );

		persistenceContext.serialize( oos );
		actionQueue.serialize( oos );

		// todo : look at optimizing these...
		oos.writeObject( loadQueryInfluencers );
	}

	@Override
	public TypeHelper getTypeHelper() {
		return getSessionFactory().getTypeHelper();
	}

	@Override
	public LobHelper getLobHelper() {
		if ( lobHelper == null ) {
			lobHelper = new LobHelperImpl( this );
		}
		return lobHelper;
	}

	private transient LobHelperImpl lobHelper;

	@Override
	public JdbcSessionContext getJdbcSessionContext() {
		return this.jdbcSessionContext;
	}

	@Override
	public void beforeTransactionCompletion() {
		LOG.tracef( "SessionImpl#beforeTransactionCompletion()" );
		flushBeforeTransactionCompletion();
		actionQueue.beforeTransactionCompletion();
		try {
			interceptor.beforeTransactionCompletion( currentHibernateTransaction );
		}
		catch (Throwable t) {
			LOG.exceptionInBeforeTransactionCompletionInterceptor( t );
		}
	}

	@Override
	public void afterTransactionCompletion(boolean successful, boolean delayed) {
		LOG.tracef( "SessionImpl#afterTransactionCompletion(successful=%s, delayed=%s)", successful, delayed );

		afterCompletionAction.doAction( successful );

		persistenceContext.afterTransactionCompletion();
		actionQueue.afterTransactionCompletion( successful );

		getEventListenerManager().transactionCompletion( successful );

		if ( factory.getStatistics().isStatisticsEnabled() ) {
			factory.getStatisticsImplementor().endTransaction( successful );
		}

		try {
			interceptor.afterTransactionCompletion( currentHibernateTransaction );
		}
		catch (Throwable t) {
			LOG.exceptionInAfterTransactionCompletionInterceptor( t );
		}

		if ( !delayed ) {
			if ( shouldAutoClose() && !isClosed() ) {
				managedClose();
			}
		}

		if ( autoClear ) {
			internalClear();
		}
	}

	private static class LobHelperImpl implements LobHelper {
		private final SessionImpl session;

		private LobHelperImpl(SessionImpl session) {
			this.session = session;
		}

		@Override
		public Blob createBlob(byte[] bytes) {
			return lobCreator().createBlob( bytes );
		}

		private LobCreator lobCreator() {
			// Always use NonContextualLobCreator.  If ContextualLobCreator is
			// used both here and in WrapperOptions, 
			return NonContextualLobCreator.INSTANCE;
		}

		@Override
		public Blob createBlob(InputStream stream, long length) {
			return lobCreator().createBlob( stream, length );
		}

		@Override
		public Clob createClob(String string) {
			return lobCreator().createClob( string );
		}

		@Override
		public Clob createClob(Reader reader, long length) {
			return lobCreator().createClob( reader, length );
		}

		@Override
		public NClob createNClob(String string) {
			return lobCreator().createNClob( string );
		}

		@Override
		public NClob createNClob(Reader reader, long length) {
			return lobCreator().createNClob( reader, length );
		}
	}

	private static class SharedSessionBuilderImpl extends SessionFactoryImpl.SessionBuilderImpl
			implements SharedSessionBuilder {
		private final SessionImpl session;
		private boolean shareTransactionContext;

		private SharedSessionBuilderImpl(SessionImpl session) {
			super( session.factory );
			this.session = session;
			super.owner( session.sessionOwner );
			super.tenantIdentifier( session.getTenantIdentifier() );
		}

		@Override
		public SessionBuilder tenantIdentifier(String tenantIdentifier) {
			// todo : is this always true?  Or just in the case of sharing JDBC resources?
			throw new SessionException( "Cannot redefine tenant identifier on child session" );
		}

		@Override
		protected TransactionCoordinator getTransactionCoordinator() {
			return shareTransactionContext ? session.transactionCoordinator : super.getTransactionCoordinator();
		}

		@Override
		protected JdbcCoordinatorImpl getJdbcCoordinator() {
			return shareTransactionContext ? session.jdbcCoordinator : super.getJdbcCoordinator();
		}

		@Override
		protected Transaction getTransaction() {
			return shareTransactionContext ? session.currentHibernateTransaction : super.getTransaction();
		}

		@Override
		protected ActionQueue.TransactionCompletionProcesses getTransactionCompletionProcesses() {
			return shareTransactionContext ?
					session.getActionQueue().getTransactionCompletionProcesses() :
					super.getTransactionCompletionProcesses();
		}

		@Override
		public SharedSessionBuilder interceptor() {
			return interceptor( session.interceptor );
		}

		@Override
		public SharedSessionBuilder connection() {
			this.shareTransactionContext = true;
			return this;
		}

		@Override
		public SharedSessionBuilder connectionReleaseMode() {
			return connectionReleaseMode( session.connectionReleaseMode );
		}

		@Override
		public SharedSessionBuilder autoJoinTransactions() {
			return autoJoinTransactions( session.autoJoinTransactions );
		}

		@Override
		public SharedSessionBuilder autoClose() {
			return autoClose( session.autoCloseSessionEnabled );
		}

		@Override
		public SharedSessionBuilder flushBeforeCompletion() {
			return flushBeforeCompletion( session.flushBeforeCompletionEnabled );
		}

		/**
		 * @deprecated Use {@link #connection()} instead
		 */
		@Override
		@Deprecated
		public SharedSessionBuilder transactionContext() {
			return connection();
		}

		@Override
		public SharedSessionBuilder interceptor(Interceptor interceptor) {
			return (SharedSessionBuilder) super.interceptor( interceptor );
		}

		@Override
		public SharedSessionBuilder noInterceptor() {
			return (SharedSessionBuilder) super.noInterceptor();
		}

		@Override
		public SharedSessionBuilder statementInspector(StatementInspector statementInspector) {
			return (SharedSessionBuilder) super.statementInspector( statementInspector );
		}

		@Override
		public SharedSessionBuilder connection(Connection connection) {
			return (SharedSessionBuilder) super.connection( connection );
		}

		@Override
		public SharedSessionBuilder connectionReleaseMode(ConnectionReleaseMode connectionReleaseMode) {
			return (SharedSessionBuilder) super.connectionReleaseMode( connectionReleaseMode );
		}

		@Override
		public SharedSessionBuilder autoJoinTransactions(boolean autoJoinTransactions) {
			return (SharedSessionBuilder) super.autoJoinTransactions( autoJoinTransactions );
		}

		@Override
		public SharedSessionBuilder autoClose(boolean autoClose) {
			return (SharedSessionBuilder) super.autoClose( autoClose );
		}

		@Override
		public SharedSessionBuilder flushBeforeCompletion(boolean flushBeforeCompletion) {
			return (SharedSessionBuilder) super.flushBeforeCompletion( flushBeforeCompletion );
		}

		@Override
		public SharedSessionBuilder eventListeners(SessionEventListener... listeners) {
			super.eventListeners( listeners );
			return this;
		}

		@Override
		public SessionBuilder clearEventListeners() {
			super.clearEventListeners();
			return this;
		}
	}

	private class CoordinatingEntityNameResolver implements EntityNameResolver {
		@Override
		public String resolveEntityName(Object entity) {
			String entityName = interceptor.getEntityName( entity );
			if ( entityName != null ) {
				return entityName;
			}

			for ( EntityNameResolver resolver : factory.iterateEntityNameResolvers() ) {
				entityName = resolver.resolveEntityName( entity );
				if ( entityName != null ) {
					break;
				}
			}

			if ( entityName != null ) {
				return entityName;
			}

			// the old-time stand-by...
			return entity.getClass().getName();
		}
	}

	private class LockRequestImpl implements LockRequest {
		private final LockOptions lockOptions;

		private LockRequestImpl(LockOptions lo) {
			lockOptions = new LockOptions();
			LockOptions.copy( lo, lockOptions );
		}

		@Override
		public LockMode getLockMode() {
			return lockOptions.getLockMode();
		}

		@Override
		public LockRequest setLockMode(LockMode lockMode) {
			lockOptions.setLockMode( lockMode );
			return this;
		}

		@Override
		public int getTimeOut() {
			return lockOptions.getTimeOut();
		}

		@Override
		public LockRequest setTimeOut(int timeout) {
			lockOptions.setTimeOut( timeout );
			return this;
		}

		@Override
		public boolean getScope() {
			return lockOptions.getScope();
		}

		@Override
		public LockRequest setScope(boolean scope) {
			lockOptions.setScope( scope );
			return this;
		}

		@Override
		public void lock(String entityName, Object object) throws HibernateException {
			fireLock( entityName, object, lockOptions );
		}

		@Override
		public void lock(Object object) throws HibernateException {
			fireLock( object, lockOptions );
		}
	}

	private class IdentifierLoadAccessImpl<T> implements IdentifierLoadAccess<T> {
		private final EntityPersister entityPersister;
		private LockOptions lockOptions;
		private CacheMode cacheMode;

		private IdentifierLoadAccessImpl(EntityPersister entityPersister) {
			this.entityPersister = entityPersister;
		}

		private IdentifierLoadAccessImpl(String entityName) {
			this( locateEntityPersister( entityName ) );
		}

		private IdentifierLoadAccessImpl(Class<T> entityClass) {
			this( locateEntityPersister( entityClass ) );
		}

		@Override
		public final IdentifierLoadAccessImpl<T> with(LockOptions lockOptions) {
			this.lockOptions = lockOptions;
			return this;
		}

		@Override
		public IdentifierLoadAccess<T> with(CacheMode cacheMode) {
			this.cacheMode = cacheMode;
			return this;
		}

		@Override
		public final T getReference(Serializable id) {
			CacheMode sessionCacheMode = getCacheMode();
			boolean cacheModeChanged = false;
			if ( cacheMode != null ) {
				// naive check for now...
				// todo : account for "conceptually equal"
				if ( cacheMode != sessionCacheMode ) {
					setCacheMode( cacheMode );
					cacheModeChanged = true;
				}
			}

			try {
				return doGetReference( id );
			}
			finally {
				if ( cacheModeChanged ) {
					// change it back
					setCacheMode( sessionCacheMode );
				}
			}
		}

		@SuppressWarnings("unchecked")
		protected T doGetReference(Serializable id) {
			if ( this.lockOptions != null ) {
				LoadEvent event = new LoadEvent( id, entityPersister.getEntityName(), lockOptions, SessionImpl.this );
				fireLoad( event, LoadEventListener.LOAD );
				return (T) event.getResult();
			}

			LoadEvent event = new LoadEvent( id, entityPersister.getEntityName(), false, SessionImpl.this );
			boolean success = false;
			try {
				fireLoad( event, LoadEventListener.LOAD );
				if ( event.getResult() == null ) {
					getFactory().getEntityNotFoundDelegate().handleEntityNotFound(
							entityPersister.getEntityName(),
							id
					);
				}
				success = true;
				return (T) event.getResult();
			}
			finally {
				afterOperation( success );
			}
		}

		@Override
		public final T load(Serializable id) {
			CacheMode sessionCacheMode = getCacheMode();
			boolean cacheModeChanged = false;
			if ( cacheMode != null ) {
				// naive check for now...
				// todo : account for "conceptually equal"
				if ( cacheMode != sessionCacheMode ) {
					setCacheMode( cacheMode );
					cacheModeChanged = true;
				}
			}

			try {
				return doLoad( id );
			}
			finally {
				if ( cacheModeChanged ) {
					// change it back
					setCacheMode( sessionCacheMode );
				}
			}
		}

		@SuppressWarnings("unchecked")
		protected final T doLoad(Serializable id) {
			if ( this.lockOptions != null ) {
				LoadEvent event = new LoadEvent( id, entityPersister.getEntityName(), lockOptions, SessionImpl.this );
				fireLoad( event, LoadEventListener.GET );
				return (T) event.getResult();
			}

			LoadEvent event = new LoadEvent( id, entityPersister.getEntityName(), false, SessionImpl.this );
			boolean success = false;
			try {
				fireLoad( event, LoadEventListener.GET );
				success = true;
			}
			catch (ObjectNotFoundException e) {
				// if session cache contains proxy for non-existing object
			}
			finally {
				afterOperation( success );
			}
			return (T) event.getResult();
		}
	}

	private class MultiIdentifierLoadAccessImpl<T> implements MultiIdentifierLoadAccess<T>, MultiLoadOptions {
		private final EntityPersister entityPersister;
		private LockOptions lockOptions;
		private CacheMode cacheMode;
		private Integer batchSize;
		private boolean sessionCheckingEnabled;

		public MultiIdentifierLoadAccessImpl(EntityPersister entityPersister) {
			this.entityPersister = entityPersister;
		}

		@Override
		public LockOptions getLockOptions() {
			return lockOptions;
		}

		@Override
		public final MultiIdentifierLoadAccessImpl<T> with(LockOptions lockOptions) {
			this.lockOptions = lockOptions;
			return this;
		}

		@Override
		public MultiIdentifierLoadAccessImpl<T> with(CacheMode cacheMode) {
			this.cacheMode = cacheMode;
			return this;
		}

		@Override
		public Integer getBatchSize() {
			return batchSize;
		}

		@Override
		public MultiIdentifierLoadAccess<T> withBatchSize(int batchSize) {
			if ( batchSize < 1 ) {
				this.batchSize = null;
			}
			else {
				this.batchSize = batchSize;
			}
			return this;
		}

		@Override
		public boolean isSessionCheckingEnabled() {
			return sessionCheckingEnabled;
		}

		@Override
		public MultiIdentifierLoadAccess<T> enableSessionCheck(boolean enabled) {
			this.sessionCheckingEnabled = enabled;
			return this;
		}

		@Override
		@SuppressWarnings("unchecked")
		public <K extends Serializable> List<T> multiLoad(K... ids) {
			CacheMode sessionCacheMode = getCacheMode();
			boolean cacheModeChanged = false;
			if ( cacheMode != null ) {
				// naive check for now...
				// todo : account for "conceptually equal"
				if ( cacheMode != sessionCacheMode ) {
					setCacheMode( cacheMode );
					cacheModeChanged = true;
				}
			}

			try {
				return entityPersister.multiLoad( ids, SessionImpl.this, this );
			}
			finally {
				if ( cacheModeChanged ) {
					// change it back
					setCacheMode( sessionCacheMode );
				}
			}
		}

		@Override
		@SuppressWarnings("unchecked")
		public <K extends Serializable> List<T> multiLoad(List<K> ids) {
			CacheMode sessionCacheMode = getCacheMode();
			boolean cacheModeChanged = false;
			if ( cacheMode != null ) {
				// naive check for now...
				// todo : account for "conceptually equal"
				if ( cacheMode != sessionCacheMode ) {
					setCacheMode( cacheMode );
					cacheModeChanged = true;
				}
			}

			try {
				return entityPersister.multiLoad( ids.toArray( new Serializable[ ids.size() ] ), SessionImpl.this, this );
			}
			finally {
				if ( cacheModeChanged ) {
					// change it back
					setCacheMode( sessionCacheMode );
				}
			}
		}
	}

	private EntityPersister locateEntityPersister(Class entityClass) {
		return factory.locateEntityPersister( entityClass );
	}

	private EntityPersister locateEntityPersister(String entityName) {
		return factory.locateEntityPersister( entityName );
	}

	private abstract class BaseNaturalIdLoadAccessImpl<T> {
		private final EntityPersister entityPersister;
		private LockOptions lockOptions;
		private boolean synchronizationEnabled = true;

		private BaseNaturalIdLoadAccessImpl(EntityPersister entityPersister) {
			this.entityPersister = entityPersister;

			if ( !entityPersister.hasNaturalIdentifier() ) {
				throw new HibernateException(
						String.format( "Entity [%s] did not define a natural id", entityPersister.getEntityName() )
				);
			}
		}

		public BaseNaturalIdLoadAccessImpl<T> with(LockOptions lockOptions) {
			this.lockOptions = lockOptions;
			return this;
		}

		protected void synchronizationEnabled(boolean synchronizationEnabled) {
			this.synchronizationEnabled = synchronizationEnabled;
		}

		protected final Serializable resolveNaturalId(Map<String, Object> naturalIdParameters) {
			performAnyNeededCrossReferenceSynchronizations();

			final ResolveNaturalIdEvent event =
					new ResolveNaturalIdEvent( naturalIdParameters, entityPersister, SessionImpl.this );
			fireResolveNaturalId( event );

			if ( event.getEntityId() == PersistenceContext.NaturalIdHelper.INVALID_NATURAL_ID_REFERENCE ) {
				return null;
			}
			else {
				return event.getEntityId();
			}
		}

		protected void performAnyNeededCrossReferenceSynchronizations() {
			if ( !synchronizationEnabled ) {
				// synchronization (this process) was disabled
				return;
			}
			if ( entityPersister.getEntityMetamodel().hasImmutableNaturalId() ) {
				// only mutable natural-ids need this processing
				return;
			}
			if ( !isTransactionInProgress() ) {
				// not in a transaction so skip synchronization
				return;
			}

			final boolean debugEnabled = LOG.isDebugEnabled();
			for ( Serializable pk : getPersistenceContext().getNaturalIdHelper()
					.getCachedPkResolutions( entityPersister ) ) {
				final EntityKey entityKey = generateEntityKey( pk, entityPersister );
				final Object entity = getPersistenceContext().getEntity( entityKey );
				final EntityEntry entry = getPersistenceContext().getEntry( entity );

				if ( entry == null ) {
					if ( debugEnabled ) {
						LOG.debug(
								"Cached natural-id/pk resolution linked to null EntityEntry in persistence context : "
										+ MessageHelper.infoString( entityPersister, pk, getFactory() )
						);
					}
					continue;
				}

				if ( !entry.requiresDirtyCheck( entity ) ) {
					continue;
				}

				// MANAGED is the only status we care about here...
				if ( entry.getStatus() != Status.MANAGED ) {
					continue;
				}

				getPersistenceContext().getNaturalIdHelper().handleSynchronization(
						entityPersister,
						pk,
						entity
				);
			}
		}

		protected final IdentifierLoadAccess getIdentifierLoadAccess() {
			final IdentifierLoadAccessImpl identifierLoadAccess = new IdentifierLoadAccessImpl( entityPersister );
			if ( this.lockOptions != null ) {
				identifierLoadAccess.with( lockOptions );
			}
			return identifierLoadAccess;
		}

		protected EntityPersister entityPersister() {
			return entityPersister;
		}
	}

	private class NaturalIdLoadAccessImpl<T> extends BaseNaturalIdLoadAccessImpl<T> implements NaturalIdLoadAccess<T> {
		private final Map<String, Object> naturalIdParameters = new LinkedHashMap<String, Object>();

		private NaturalIdLoadAccessImpl(EntityPersister entityPersister) {
			super( entityPersister );
		}

		private NaturalIdLoadAccessImpl(String entityName) {
			this( locateEntityPersister( entityName ) );
		}

		private NaturalIdLoadAccessImpl(Class entityClass) {
			this( locateEntityPersister( entityClass ) );
		}

		@Override
		public NaturalIdLoadAccessImpl<T> with(LockOptions lockOptions) {
			return (NaturalIdLoadAccessImpl<T>) super.with( lockOptions );
		}

		@Override
		public NaturalIdLoadAccess<T> using(String attributeName, Object value) {
			naturalIdParameters.put( attributeName, value );
			return this;
		}

		@Override
		public NaturalIdLoadAccessImpl<T> setSynchronizationEnabled(boolean synchronizationEnabled) {
			super.synchronizationEnabled( synchronizationEnabled );
			return this;
		}

		@Override
		@SuppressWarnings("unchecked")
		public final T getReference() {
			final Serializable entityId = resolveNaturalId( this.naturalIdParameters );
			if ( entityId == null ) {
				return null;
			}
			return (T) this.getIdentifierLoadAccess().getReference( entityId );
		}

		@Override
		@SuppressWarnings("unchecked")
		public final T load() {
			final Serializable entityId = resolveNaturalId( this.naturalIdParameters );
			if ( entityId == null ) {
				return null;
			}
			try {
				return (T) this.getIdentifierLoadAccess().load( entityId );
			}
			catch (EntityNotFoundException enf) {
				// OK
			}
			catch (ObjectNotFoundException nf) {
				// OK
			}
			return null;
		}
	}

	private class SimpleNaturalIdLoadAccessImpl<T> extends BaseNaturalIdLoadAccessImpl<T>
			implements SimpleNaturalIdLoadAccess<T> {
		private final String naturalIdAttributeName;

		private SimpleNaturalIdLoadAccessImpl(EntityPersister entityPersister) {
			super( entityPersister );

			if ( entityPersister.getNaturalIdentifierProperties().length != 1 ) {
				throw new HibernateException(
						String.format(
								"Entity [%s] did not define a simple natural id",
								entityPersister.getEntityName()
						)
				);
			}

			final int naturalIdAttributePosition = entityPersister.getNaturalIdentifierProperties()[0];
			this.naturalIdAttributeName = entityPersister.getPropertyNames()[naturalIdAttributePosition];
		}

		private SimpleNaturalIdLoadAccessImpl(String entityName) {
			this( locateEntityPersister( entityName ) );
		}

		private SimpleNaturalIdLoadAccessImpl(Class entityClass) {
			this( locateEntityPersister( entityClass ) );
		}

		@Override
		public final SimpleNaturalIdLoadAccessImpl<T> with(LockOptions lockOptions) {
			return (SimpleNaturalIdLoadAccessImpl<T>) super.with( lockOptions );
		}

		private Map<String, Object> getNaturalIdParameters(Object naturalIdValue) {
			return Collections.singletonMap( naturalIdAttributeName, naturalIdValue );
		}

		@Override
		public SimpleNaturalIdLoadAccessImpl<T> setSynchronizationEnabled(boolean synchronizationEnabled) {
			super.synchronizationEnabled( synchronizationEnabled );
			return this;
		}

		@Override
		@SuppressWarnings("unchecked")
		public T getReference(Object naturalIdValue) {
			final Serializable entityId = resolveNaturalId( getNaturalIdParameters( naturalIdValue ) );
			if ( entityId == null ) {
				return null;
			}
			return (T) this.getIdentifierLoadAccess().getReference( entityId );
		}

		@Override
		@SuppressWarnings("unchecked")
		public T load(Object naturalIdValue) {
			final Serializable entityId = resolveNaturalId( getNaturalIdParameters( naturalIdValue ) );
			if ( entityId == null ) {
				return null;
			}
			try {
				return (T) this.getIdentifierLoadAccess().load( entityId );
			}
			catch (EntityNotFoundException enf) {
				// OK
			}
			catch (ObjectNotFoundException nf) {
				// OK
			}
			return null;
		}
	}

	@Override
	public void afterTransactionBegin() {
		errorIfClosed();
		interceptor.afterTransactionBegin( currentHibernateTransaction );
	}

	@Override
	public void flushBeforeTransactionCompletion() {
		boolean flush = isTransactionFlushable() && managedFlushChecker.shouldDoManagedFlush( this );
		try {
			if ( flush ) {
				managedFlush();
			}
		}
		catch (HibernateException he) {
			throw exceptionMapper.mapManagedFlushFailure( "error during managed flush", he );
		}
		catch (RuntimeException re) {
			throw exceptionMapper.mapManagedFlushFailure( "error during managed flush", re );
		}
	}

	private boolean isTransactionFlushable() {
		if ( currentHibernateTransaction == null ) {
			// assume it is flushable - CMT, auto-commit, etc
			return true;
		}
		final TransactionStatus status = currentHibernateTransaction.getStatus();
		return status == TransactionStatus.ACTIVE || status == TransactionStatus.COMMITTING;
	}

	private static final ExceptionMapper STANDARD_EXCEPTION_MAPPER = new ExceptionMapper() {
		@Override
		public RuntimeException mapStatusCheckFailure(String message, SystemException systemException) {
			return new TransactionException(
					"could not determine transaction status in beforeCompletion()",
					systemException
			);
		}

		@Override
		public RuntimeException mapManagedFlushFailure(String message, RuntimeException failure) {
			LOG.unableToPerformManagedFlush( failure.getMessage() );
			return failure;
		}
	};

	private static final AfterCompletionAction STANDARD_AFTER_COMPLETION_ACTION = new AfterCompletionAction() {
		@Override
		public void doAction(boolean successful) {
			// nothing to do by default.
		}
	};

	private static final ManagedFlushChecker STANDARD_MANAGED_FLUSH_CHECKER = new ManagedFlushChecker() {
		@Override
		public boolean shouldDoManagedFlush(SessionImpl session) {
			boolean isFlushModeNever = session.isFlushModeNever();
			return ( !isFlushModeNever &&
					!session.flushBeforeCompletionEnabled ) ||
					!session.isClosed()
							&& !isFlushModeNever
							&& session.flushBeforeCompletionEnabled;
		}
	};

	private JtaPlatform getJtaPlatform() {
		return factory.getServiceRegistry().getService( JtaPlatform.class );
	}
}

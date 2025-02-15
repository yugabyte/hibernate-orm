/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.id.enhanced;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.hibernate.AssertionFailure;
import org.hibernate.LockMode;
import org.hibernate.LockOptions;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.relational.Database;
import org.hibernate.boot.model.relational.InitCommand;
import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.QualifiedName;
import org.hibernate.boot.model.relational.SqlStringGenerationContext;
import org.hibernate.dialect.Dialect;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;
import org.hibernate.engine.jdbc.internal.FormatStyle;
import org.hibernate.engine.jdbc.spi.SqlStatementLogger;
import org.hibernate.engine.spi.SessionEventListenerManager;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.event.jfr.JdbcPreparedStatementExecutionEvent;
import org.hibernate.event.jfr.internal.JfrEventManager;
import org.hibernate.event.jfr.JdbcPreparedStatementCreationEvent;
import org.hibernate.id.ExportableColumn;
import org.hibernate.id.IdentifierGenerationException;
import org.hibernate.id.IdentifierGeneratorHelper;
import org.hibernate.id.IntegralDataTypeHolder;
import org.hibernate.internal.CoreMessageLogger;
import org.hibernate.jdbc.AbstractReturningWork;
import org.hibernate.mapping.Table;
import org.hibernate.type.StandardBasicTypes;

import org.jboss.logging.Logger;

/**
 * Describes a table used to mimic sequence behavior
 *
 * @author Steve Ebersole
 */
public class TableStructure implements DatabaseStructure {
	private static final CoreMessageLogger LOG = Logger.getMessageLogger(
			CoreMessageLogger.class,
			TableStructure.class.getName()
	);

	private final QualifiedName logicalQualifiedTableName;
	private final Identifier logicalValueColumnNameIdentifier;
	private final int initialValue;
	private final int incrementSize;
	private final Class numberType;

	private String contributor;

	private QualifiedName physicalTableName;
	private String valueColumnNameText;

	private String selectQuery;
	private String updateQuery;

	private boolean applyIncrementSizeToSourceValues;
	private int accessCounter;


	public TableStructure(
			JdbcEnvironment jdbcEnvironment,
			String contributor,
			QualifiedName qualifiedTableName,
			Identifier valueColumnNameIdentifier,
			int initialValue,
			int incrementSize,
			Class numberType) {
		this.contributor = contributor;
		this.logicalQualifiedTableName = qualifiedTableName;
		this.logicalValueColumnNameIdentifier = valueColumnNameIdentifier;

		this.initialValue = initialValue;
		this.incrementSize = incrementSize;
		this.numberType = numberType;
	}

	@Override
	public QualifiedName getPhysicalName() {
		return physicalTableName;
	}

	@Override
	public int getInitialValue() {
		return initialValue;
	}

	@Override
	public int getIncrementSize() {
		return incrementSize;
	}

	@Override
	public int getTimesAccessed() {
		return accessCounter;
	}

	@Override
	public String[] getAllSqlForTests() {
		return new String[] { selectQuery, updateQuery };
	}

	@Override
	public void prepare(Optimizer optimizer) {
		applyIncrementSizeToSourceValues = optimizer.applyIncrementSizeToSourceValues();
	}

	private IntegralDataTypeHolder makeValue() {
		return IdentifierGeneratorHelper.getIntegralDataTypeHolder( numberType );
	}

	@Override
	public AccessCallback buildCallback(final SharedSessionContractImplementor session) {
		final SqlStatementLogger statementLogger = session.getFactory().getJdbcServices()
				.getSqlStatementLogger();
		if ( selectQuery == null || updateQuery == null ) {
			throw new AssertionFailure( "SequenceStyleGenerator's TableStructure was not properly initialized" );
		}

		final SessionEventListenerManager statsCollector = session.getEventListenerManager();

		return new AccessCallback() {
			@Override
			public IntegralDataTypeHolder getNextValue() {
				return session.getTransactionCoordinator().createIsolationDelegate().delegateWork(
						new AbstractReturningWork<IntegralDataTypeHolder>() {
							@Override
							public IntegralDataTypeHolder execute(Connection connection) throws SQLException {
								final IntegralDataTypeHolder value = makeValue();
								int rows;
								do {
									try (PreparedStatement selectStatement = prepareStatement(
											connection,
											selectQuery,
											statementLogger,
											statsCollector
									)) {
										final ResultSet selectRS = executeQuery( selectStatement, statsCollector, selectQuery );
										if ( !selectRS.next() ) {
											final String err = "could not read a hi value - you need to populate the table: " + physicalTableName;
											LOG.error( err );
											throw new IdentifierGenerationException( err );
										}
										value.initialize( selectRS, 1 );
										selectRS.close();
									}
									catch (SQLException sqle) {
										LOG.error( "could not read a hi value", sqle );
										throw sqle;
									}


									try (PreparedStatement updatePS = prepareStatement(
											connection,
											updateQuery,
											statementLogger,
											statsCollector
									)) {
										final int increment = applyIncrementSizeToSourceValues ? incrementSize : 1;
										final IntegralDataTypeHolder updateValue = value.copy().add( increment );
										updateValue.bind( updatePS, 1 );
										value.bind( updatePS, 2 );
										rows = executeUpdate( updatePS, statsCollector, updateQuery );
									}
									catch (SQLException e) {
										LOG.unableToUpdateQueryHiValue( physicalTableName.render(), e );
										throw e;
									}
								} while ( rows == 0 );

								accessCounter++;

								return value;
							}
						},
						true
				);
			}

			@Override
			public String getTenantIdentifier() {
				return session.getTenantIdentifier();
			}
		};
	}

	private PreparedStatement prepareStatement(
			Connection connection,
			String sql,
			SqlStatementLogger statementLogger,
			SessionEventListenerManager statsCollector) throws SQLException {
		statementLogger.logStatement( sql, FormatStyle.BASIC.getFormatter() );
		final JdbcPreparedStatementCreationEvent jdbcPreparedStatementCreation = JfrEventManager.beginJdbcPreparedStatementCreationEvent();
		try {
			statsCollector.jdbcPrepareStatementStart();
			return connection.prepareStatement( sql );
		}
		finally {
			JfrEventManager.completeJdbcPreparedStatementCreationEvent( jdbcPreparedStatementCreation, sql );
			statsCollector.jdbcPrepareStatementEnd();
		}
	}

	private int executeUpdate(PreparedStatement ps, SessionEventListenerManager statsCollector, String sql ) throws SQLException {
		final JdbcPreparedStatementExecutionEvent jdbcPreparedStatementExecutionEvent = JfrEventManager.beginJdbcPreparedStatementExecutionEvent();
		try {
			statsCollector.jdbcExecuteStatementStart();
			return ps.executeUpdate();
		}
		finally {
			JfrEventManager.completeJdbcPreparedStatementExecutionEvent( jdbcPreparedStatementExecutionEvent, sql );
			statsCollector.jdbcExecuteStatementEnd();
		}

	}

	private ResultSet executeQuery(PreparedStatement ps, SessionEventListenerManager statsCollector, String sql ) throws SQLException {
		final JdbcPreparedStatementExecutionEvent jdbcPreparedStatementExecutionEvent = JfrEventManager.beginJdbcPreparedStatementExecutionEvent();
		try {
			statsCollector.jdbcExecuteStatementStart();
			return ps.executeQuery();
		}
		finally {
			JfrEventManager.completeJdbcPreparedStatementExecutionEvent( jdbcPreparedStatementExecutionEvent, sql );
			statsCollector.jdbcExecuteStatementEnd();
		}
	}

	@Override
	public boolean isPhysicalSequence() {
		return false;
	}

	@Override
	public void registerExportables(Database database) {
		final JdbcEnvironment jdbcEnvironment = database.getJdbcEnvironment();
		final Dialect dialect = jdbcEnvironment.getDialect();

		final Namespace namespace = database.locateNamespace(
				logicalQualifiedTableName.getCatalogName(),
				logicalQualifiedTableName.getSchemaName()
		);

		Table table = namespace.locateTable( logicalQualifiedTableName.getObjectName() );
		boolean tableCreated = false;
		if ( table == null ) {
			table = namespace.createTable(
					logicalQualifiedTableName.getObjectName(),
					(identifier) -> new Table( contributor, namespace, identifier, false )
			);
			tableCreated = true;
		}
		this.physicalTableName = table.getQualifiedTableName();

		valueColumnNameText = logicalValueColumnNameIdentifier.render( dialect );
		if ( tableCreated ) {
			ExportableColumn valueColumn = new ExportableColumn(
					database,
					table,
					valueColumnNameText,
					database.getTypeConfiguration().getBasicTypeRegistry().resolve( StandardBasicTypes.LONG )
			);

			table.addColumn( valueColumn );

			table.addInitCommand( context -> new InitCommand( "insert into "
					+ context.format( physicalTableName ) + " values ( " + initialValue + " )" ) );
		}
	}

	@Override
	public void initialize(SqlStringGenerationContext context) {
		Dialect dialect = context.getDialect();

		String formattedPhysicalTableName = context.format( physicalTableName );

		this.selectQuery = "select " + valueColumnNameText + " as id_val" +
				" from " + dialect.appendLockHint( new LockOptions( LockMode.PESSIMISTIC_WRITE ), formattedPhysicalTableName ) +
				dialect.getForUpdateString();

		this.updateQuery = "update " + formattedPhysicalTableName +
				" set " + valueColumnNameText + "= ?" +
				" where " + valueColumnNameText + "=?";
	}
}

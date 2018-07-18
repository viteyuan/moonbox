package moonbox.core.datasys.oracle

import java.net.InetAddress
import java.sql.{Connection, DriverManager}
import java.util.Properties

import moonbox.common.MbLogging
import moonbox.core.datasys.{DataSystem, Insertable, Pushdownable}
import moonbox.core.execution.standalone.DataTable
import moonbox.core.util.MbIterator
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate._
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.rdd.MbJdbcRDD
import org.apache.spark.sql.sqlbuilder.{MbOracleDialect, MbSqlBuilder}
import org.apache.spark.sql.{DataFrame, Row, SaveMode, SparkSession}

import scala.collection.mutable.ArrayBuffer

class OracleDataSystem(props: Map[String, String])
	extends DataSystem(props) with Pushdownable with Insertable with MbLogging {

	require(contains("type", "url", "user", "password"))

	override def tableNames(): Seq[String] = {
		val tables = new ArrayBuffer[String]()
		val connection = getConnection()
		val resultSet = connection.createStatement().executeQuery("SELECT table_name FROM user_tables")
		while (resultSet.next()) {
			tables.+=:(resultSet.getString(1))
		}
		connection.close()
		tables
	}

	override def tableName(): String = {
		props("dbtable")
	}

	override def tableProperties(tableName: String): Map[String, String] = {
		props.+("dbtable" -> tableName)
	}

	override def buildQuery(plan: LogicalPlan): DataTable = {
		val sqlBuilder = new MbSqlBuilder(plan, new MbOracleDialect)
		val sql = sqlBuilder.toSQL
		val schema = sqlBuilder.finalLogicalPlan.schema
		logInfo(s"query sql: $sql")
		val iter = new MbIterator[Row] {
			val conn = getConnection()
			val statement = conn.createStatement()
			val resultSet = statement.executeQuery(sql)

			override def close(): Unit = {
				try {
					if (null != resultSet) {
						resultSet.close()
					}
				} catch {
					case e: Exception => logWarning("Exception closing resultset", e)
				}
				try {
					if (null != statement) {
						statement.isClosed
					}
				} catch {
					case e: Exception => logWarning("Exception closing statement", e)
				}
				try {
					if (null != conn) {
						conn.close()
					}
					logInfo("closed connection")
				} catch {
					case e: Exception => logWarning("Exception closing connection", e)
				}
			}

			override def getNext(): Row = {
				if (resultSet != null && resultSet.next()) {
					Row(MbJdbcRDD.resultSetToObjectArray(resultSet):_*)
				} else {
					finished = true
					null.asInstanceOf[Row]
				}
			}
		}
		new DataTable(iter, schema, () => iter.closeIfNeeded())
	}

	override def isSupportAll: Boolean = false

	override def fastEquals(other: DataSystem): Boolean = {
		other match {
			case oracle: OracleDataSystem =>
				socket == oracle.socket
			case _ => false
		}
	}

	override def buildScan(plan: LogicalPlan, sparkSession: SparkSession): DataFrame = {
		val sqlBuilder = new MbSqlBuilder(plan, new MbOracleDialect)
		val sql = sqlBuilder.toSQL
		logInfo(s"pushdown sql : $sql")
		val rdd = new MbJdbcRDD(
			sparkSession.sparkContext,
			getConnection,
			sql,
			rs => Row(MbJdbcRDD.resultSetToObjectArray(rs):_*)
		)
		val schema = sqlBuilder.finalLogicalPlan.schema
		sparkSession.createDataFrame(rdd, schema)
	}

	override val supportedOperators: Seq[Class[_]] = Seq(
		classOf[Project],
		classOf[Filter],
		classOf[Aggregate],
		classOf[Sort],
		classOf[Join],
		classOf[GlobalLimit],
		classOf[LocalLimit],
		classOf[Subquery],
		classOf[SubqueryAlias]
	)
	override val supportedUDF: Seq[String] = Seq()

	override val supportedExpressions: Seq[Class[_]] = Seq(
		classOf[Literal], classOf[AttributeReference], classOf[Alias], classOf[AggregateExpression],
		classOf[Abs], classOf[Coalesce], classOf[Greatest], classOf[If],
		classOf[IsNull], classOf[IsNotNull], classOf[Least], classOf[NaNvl],
		classOf[NullIf], classOf[Nvl], classOf[Nvl2], classOf[CaseWhen],
		classOf[Acos], classOf[Asin], classOf[Atan], classOf[Atan2],
		classOf[Ceil], classOf[Cos], classOf[Cosh], classOf[Exp],
		classOf[Floor], classOf[Logarithm], classOf[Log], classOf[Pow],
		classOf[Round], classOf[Signum], classOf[Sin], classOf[Sinh],
		classOf[Sqrt], classOf[Tan], classOf[Tanh], classOf[Add],
		classOf[Subtract], classOf[Multiply], classOf[Divide], classOf[Remainder],
		classOf[Average], classOf[Corr], classOf[Count], classOf[CovPopulation],
		classOf[CovSample], classOf[First], classOf[Last], classOf[Max],
		classOf[Min], classOf[StddevSamp], classOf[StddevPop], classOf[Sum],
		classOf[VarianceSamp], classOf[VariancePop], classOf[Ascii],
		classOf[Concat], classOf[Decode], classOf[Encode], classOf[InitCap],
		classOf[StringInstr], classOf[Lower], classOf[Length], classOf[Like],
		classOf[StringLPad], classOf[StringTrimLeft], classOf[StringRPad],
		classOf[StringTrimRight], classOf[SoundEx], classOf[Substring],
		classOf[StringTranslate], classOf[StringTrim], classOf[Upper],
		classOf[AddMonths], classOf[CurrentDate], classOf[CurrentTimestamp],
		classOf[LastDay], classOf[MonthsBetween], classOf[NextDay],
		classOf[ParseToTimestamp], classOf[ParseToDate], classOf[TruncDate],
		classOf[Grouping], classOf[GroupingID], classOf[Rollup], classOf[Lead],
		classOf[Lag], classOf[RowNumber], classOf[CumeDist], classOf[NTile],
		classOf[Rank], classOf[DenseRank], classOf[PercentRank],
		classOf[And], classOf[In], classOf[Not], classOf[Or], classOf[EqualNullSafe],
		classOf[EqualTo], classOf[GreaterThan], classOf[GreaterThanOrEqual],
		classOf[LessThan], classOf[LessThanOrEqual], classOf[BitwiseAnd],
		classOf[BitwiseNot], classOf[BitwiseOr], classOf[BitwiseXor], classOf[Cast]
	)

	override val beGoodAtOperators: Seq[Class[_]] = Seq(
		classOf[Join],
		classOf[GlobalLimit],
		classOf[LocalLimit],
		classOf[Aggregate]
	)

	override val supportedJoinTypes: Seq[JoinType] = Seq(
		Inner, Cross, LeftOuter, RightOuter
	)

	override def insert(table: DataTable, saveMode: SaveMode): Unit = {

	}

	private def socket: (String, Int) = {
		val url = props("url").toLowerCase
		val removeProtocol = url.stripPrefix("jdbc:oracle:thin:@")
		val hostPort = removeProtocol.substring(0, removeProtocol.lastIndexOf(':')).split(":")
		val host = hostPort(0)
		val port = if (hostPort.length > 1) hostPort(1).toInt else 1521
		(InetAddress.getByName(host).getHostAddress, port)
	}

	private def getConnection: () => Connection = {
		val p = new Properties()
		props.foreach { case (k, v) => p.put(k, v) }
		((url: String, props: Properties) => {
			() => {
				Class.forName("oracle.jdbc.driver.OracleDriver")
				DriverManager.getConnection(url, props)
			}
		})(props("url"), p)
	}

	override def test(): Boolean = {
		var connection: Connection = null
		try  {
			connection = getConnection()
			if (connection != null) {
				true
			} else {
				false
			}
		} catch {
			case e: Exception =>
				false
		} finally {
			if (connection != null) {
				connection.close()
			}
		}
	}
}
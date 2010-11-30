package org.scalaquery.ql.extended

import java.sql.Types
import org.scalaquery.SQueryException
import org.scalaquery.ql._
import org.scalaquery.ql.basic._
import org.scalaquery.util._

/**
 * ScalaQuery driver for <a href="http://www.hsqldb.org/">HyperSQL</a>
 * (starting with version 2.0).
 * 
 * <p>This driver implements the ExtendedProfile with the following
 * limitations:</p>
 * <ul>
 *   <li><code>Sequence.curr</code> to get the current value of a sequence is
 *     not supported by Hsqldb. Trying to generate SQL code which uses this
 *     feature throws a SQueryException.</li>
 * </ul>
 * 
 * @author szeiger
 */
class HsqldbDriver extends ExtendedProfile { self =>

  type ImplicitT = ExtendedImplicitConversions[HsqldbDriver]
  type TypeMapperDelegatesT = HsqldbTypeMapperDelegates

  val Implicit = new ExtendedImplicitConversions[HsqldbDriver] {
    implicit val scalaQueryDriver = self
  }

  val typeMapperDelegates = new HsqldbTypeMapperDelegates

  override def createQueryBuilder(query: Query[_], nc: NamingContext) = new HsqldbQueryBuilder(query, nc, None, this)
  override def buildTableDDL(table: AbstractBasicTable[_]): DDL = new HsqldbDDLBuilder(table, this).buildDDL
  override def buildSequenceDDL(seq: Sequence[_]): DDL = new HsqldbSequenceDDLBuilder(seq, this).buildDDL
}

object HsqldbDriver extends HsqldbDriver

class HsqldbTypeMapperDelegates extends BasicTypeMapperDelegates {
  override val byteArrayTypeMapperDelegate = new BasicTypeMapperDelegates.ByteArrayTypeMapperDelegate {
    override val sqlTypeName = "LONGVARBINARY"
  }
}

class HsqldbDDLBuilder(table: AbstractBasicTable[_], profile: HsqldbDriver) extends BasicDDLBuilder(table, profile) {
  import profile.sqlUtils._

  protected class HsqldbColumnDDLBuilder(column: NamedColumn[_]) extends BasicColumnDDLBuilder(column) {
    override protected def appendOptions(sb: StringBuilder) {
      if(autoIncrement) sb append " GENERATED BY DEFAULT AS IDENTITY(START WITH 1) PRIMARY KEY"
      else if(primaryKey) sb append " PRIMARY KEY"
      if(defaultLiteral ne null) sb append " DEFAULT " append defaultLiteral
      if(notNull) sb append " NOT NULL"
    }
  }

  override protected def createColumnDDLBuilder(c: NamedColumn[_]) = new HsqldbColumnDDLBuilder(c)

  override protected def createIndex(idx: Index) = {
    if(idx.unique) {
      /* Create a UNIQUE CONSTRAINT (with an automatically generated backing
       * index) because Hsqldb does not allow a FOREIGN KEY CONSTRAINT to
       * reference columns which have a UNIQUE INDEX but not a nominal UNIQUE
       * CONSTRAINT. */
      val sb = new StringBuilder append "ALTER TABLE " append quoteIdentifier(table.tableName) append " ADD "
      sb append "CONSTRAINT " append quoteIdentifier(idx.name) append " UNIQUE("
      addIndexColumnList(idx.on, sb, idx.table.tableName)
      sb append ")"
      sb.toString
    } else super.createIndex(idx)
  }
}

class HsqldbQueryBuilder(_query: Query[_], _nc: NamingContext, parent: Option[BasicQueryBuilder], profile: HsqldbDriver)
extends BasicQueryBuilder(_query, _nc, parent, profile) {

  import ExtendedQueryOps._
  import profile.sqlUtils._

  override type Self = HsqldbQueryBuilder

  protected def createSubQueryBuilder(query: Query[_], nc: NamingContext) =
    new HsqldbQueryBuilder(query, nc, Some(this), profile)

  override protected def innerBuildSelectNoRewrite(b: SQLBuilder, rename: Boolean) {
    query.typedModifiers[TakeDrop] match {
      case TakeDrop(Some(0), _) :: _ =>
        /* Hsqldb does not accept LIMIT 0, so we use this workaround
         * to force the query to return no results */
        b += "SELECT * FROM ("
        super.innerBuildSelectNoRewrite(b, rename)
        b += ") WHERE FALSE"
      case _ =>
        super.innerBuildSelectNoRewrite(b, rename)
    }
  }

  override protected def innerExpr(c: Node, b: SQLBuilder): Unit = c match {

    case ColumnOps.Concat(l, r) => b += '('; expr(l, b); b += "||"; expr(r, b); b += ')'

    case c @ ConstColumn(v: String) if v ne null =>
      /* Hsqldb treats string literals as type CHARACTER and pads them with
       * spaces in some expressions, so we cast all string literals to
       * VARCHAR. The length is only 16M instead of 2^31-1 in order to leave
       * enough room for concatenating strings (which extends the size even if
       * it is not needed). */
      if(c.typeMapper(profile).sqlType == Types.CHAR) super.innerExpr(c, b)
      else {
        b += "cast("
        super.innerExpr(c, b)
        b += " as varchar(16777216))"
      }

    /* Hsqldb uses the SQL:2008 syntax for NEXTVAL */
    case Sequence.Nextval(seq) => b += "(next value for " += quoteIdentifier(seq.name) += ")"

    case Sequence.Currval(seq) => throw new SQueryException("Hsqldb does not support CURRVAL")

    case _ => super.innerExpr(c, b)
  }

  override protected def appendClauses(b: SQLBuilder): Unit = {
    super.appendClauses(b)
    appendLimitClause(b)
  }

  override protected def insertFromClauses() {
    super.insertFromClauses()
    if(fromSlot.isEmpty) fromSlot += " FROM (VALUES (0))"
  }

  protected def appendLimitClause(b: SQLBuilder): Unit = query.typedModifiers[TakeDrop].lastOption.foreach {
    case TakeDrop(Some(0), _) => () // handled above in innerBuildSelectNoRewrite
    case TakeDrop(Some(t), Some(d)) => b += " LIMIT " += t += " OFFSET " += d
    case TakeDrop(Some(t), None) => b += " LIMIT " += t
    case TakeDrop(None, Some(d)) => b += " OFFSET " += d
    case _ =>
  }
}

class HsqldbSequenceDDLBuilder[T](seq: Sequence[T], profile: HsqldbDriver) extends BasicSequenceDDLBuilder(seq, profile) {
  import profile.sqlUtils._

  override def buildDDL: DDL = {
    import seq.integral._
    val increment = seq._increment.getOrElse(one)
    val desc = increment < zero
    val start = seq._start.getOrElse(if(desc) -1 else 1)
    val b = new StringBuilder append "CREATE SEQUENCE " append quoteIdentifier(seq.name)
    seq._increment.foreach { b append " INCREMENT BY " append _ }
    seq._minValue.foreach { b append " MINVALUE " append _ }
    seq._maxValue.foreach { b append " MAXVALUE " append _ }
    /* The START value in Hsqldb defaults to 0 instead of the more
     * conventional 1/-1 so we rewrite it to make 1/-1 the default. */
    if(start != 0) b append " START WITH " append start
    if(seq._cycle) b append " CYCLE"
    new DDL {
      val createPhase1 = Iterable(b.toString)
      val createPhase2 = Nil
      val dropPhase1 = Nil
      val dropPhase2 = Iterable("DROP SEQUENCE " + quoteIdentifier(seq.name))
    }
  }
}

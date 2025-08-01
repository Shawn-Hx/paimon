/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.parser.extensions

import org.apache.paimon.spark.SparkProcedures

import org.antlr.v4.runtime._
import org.antlr.v4.runtime.atn.PredictionMode
import org.antlr.v4.runtime.misc.{Interval, ParseCancellationException}
import org.antlr.v4.runtime.tree.TerminalNodeImpl
import org.apache.spark.internal.Logging
import org.apache.spark.sql.{AnalysisException, PaimonSparkSession, SparkSession}
import org.apache.spark.sql.catalyst.{FunctionIdentifier, TableIdentifier}
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.parser.{ParseException, ParserInterface}
import org.apache.spark.sql.catalyst.parser.extensions.PaimonSqlExtensionsParser.{NonReservedContext, QuotedIdentifierContext}
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.internal.VariableSubstitution
import org.apache.spark.sql.types.{DataType, StructType}

import java.util.Locale

import scala.collection.JavaConverters._

/* This file is based on source code from the Iceberg Project (http://iceberg.apache.org/), licensed by the Apache
 * Software Foundation (ASF) under the Apache License, Version 2.0. See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership. */

/**
 * The implementation of [[ParserInterface]] that parsers the sql extension.
 *
 * <p>Most of the content of this class is referenced from Iceberg's
 * IcebergSparkSqlExtensionsParser.
 *
 * @param delegate
 *   The extension parser.
 */
abstract class AbstractPaimonSparkSqlExtensionsParser(val delegate: ParserInterface)
  extends org.apache.spark.sql.catalyst.parser.ParserInterface
  with Logging {

  private lazy val substitutor = new VariableSubstitution()
  private lazy val astBuilder = new PaimonSqlExtensionsAstBuilder(delegate)

  /** Parses a string to a LogicalPlan. */
  override def parsePlan(sqlText: String): LogicalPlan = {
    val sqlTextAfterSubstitution = substitutor.substitute(sqlText)
    if (isPaimonCommand(sqlTextAfterSubstitution)) {
      parse(sqlTextAfterSubstitution)(parser => astBuilder.visit(parser.singleStatement()))
        .asInstanceOf[LogicalPlan]
    } else {
      RewritePaimonViewCommands(PaimonSparkSession.active).apply(delegate.parsePlan(sqlText))
    }
  }

  /** Parses a string to an Expression. */
  override def parseExpression(sqlText: String): Expression =
    delegate.parseExpression(sqlText)

  /** Parses a string to a TableIdentifier. */
  override def parseTableIdentifier(sqlText: String): TableIdentifier =
    delegate.parseTableIdentifier(sqlText)

  /** Parses a string to a FunctionIdentifier. */
  override def parseFunctionIdentifier(sqlText: String): FunctionIdentifier =
    delegate.parseFunctionIdentifier(sqlText)

  /**
   * Creates StructType for a given SQL string, which is a comma separated list of field definitions
   * which will preserve the correct Hive metadata.
   */
  override def parseTableSchema(sqlText: String): StructType =
    delegate.parseTableSchema(sqlText)

  /** Parses a string to a DataType. */
  override def parseDataType(sqlText: String): DataType =
    delegate.parseDataType(sqlText)

  /** Parses a string to a multi-part identifier. */
  override def parseMultipartIdentifier(sqlText: String): Seq[String] =
    delegate.parseMultipartIdentifier(sqlText)

  /** Returns whether SQL text is command. */
  private def isPaimonCommand(sqlText: String): Boolean = {
    val normalized = sqlText
      .toLowerCase(Locale.ROOT)
      .trim()
      .replaceAll("--.*?\\n", " ")
      .replaceAll("\\s+", " ")
      .replaceAll("/\\*.*?\\*/", " ")
      .replaceAll("`", "")
      .trim()
    isPaimonProcedure(normalized) || isTagRefDdl(normalized)
  }

  // All builtin paimon procedures are under the 'sys' namespace
  private def isPaimonProcedure(normalized: String): Boolean = {
    normalized.startsWith("call") &&
    SparkProcedures.names().asScala.map("sys." + _).exists(normalized.contains)
  }

  private def isTagRefDdl(normalized: String): Boolean = {
    normalized.startsWith("show tags") ||
    (normalized.startsWith("alter table") &&
      (normalized.contains("create tag") ||
        normalized.contains("replace tag") ||
        normalized.contains("rename tag") ||
        normalized.contains("delete tag")))
  }

  protected def parse[T](command: String)(toResult: PaimonSqlExtensionsParser => T): T = {
    val lexer = new PaimonSqlExtensionsLexer(
      new UpperCaseCharStream(CharStreams.fromString(command)))
    lexer.removeErrorListeners()
    lexer.addErrorListener(PaimonParseErrorListener)

    val tokenStream = new CommonTokenStream(lexer)
    val parser = new PaimonSqlExtensionsParser(tokenStream)
    parser.addParseListener(PaimonSqlExtensionsPostProcessor)
    parser.removeErrorListeners()
    parser.addErrorListener(PaimonParseErrorListener)

    try {
      try {
        parser.getInterpreter.setPredictionMode(PredictionMode.SLL)
        toResult(parser)
      } catch {
        case _: ParseCancellationException =>
          tokenStream.seek(0)
          parser.reset()
          parser.getInterpreter.setPredictionMode(PredictionMode.LL)
          toResult(parser)
      }
    } catch {
      case e: PaimonParseException if e.command.isDefined =>
        throw e
      case e: PaimonParseException =>
        throw e.withCommand(command)
      case e: AnalysisException =>
        val position = Origin(e.line, e.startPosition)
        throw new PaimonParseException(Option(command), e.message, position, position)
    }
  }

  def parseQuery(sqlText: String): LogicalPlan =
    parsePlan(sqlText)
}

/* Copied from Apache Spark's to avoid dependency on Spark Internals */
class UpperCaseCharStream(wrapped: CodePointCharStream) extends CharStream {
  override def consume(): Unit = wrapped.consume()
  override def getSourceName: String = wrapped.getSourceName
  override def index(): Int = wrapped.index
  override def mark(): Int = wrapped.mark
  override def release(marker: Int): Unit = wrapped.release(marker)
  override def seek(where: Int): Unit = wrapped.seek(where)
  override def size(): Int = wrapped.size

  override def getText(interval: Interval): String = wrapped.getText(interval)

  // scalastyle:off
  override def LA(i: Int): Int = {
    val la = wrapped.LA(i)
    if (la == 0 || la == IntStream.EOF) la
    else Character.toUpperCase(la)
  }
  // scalastyle:on
}

/** The post-processor validates & cleans-up the parse tree during the parse process. */
case object PaimonSqlExtensionsPostProcessor extends PaimonSqlExtensionsBaseListener {

  /** Removes the back ticks from an Identifier. */
  override def exitQuotedIdentifier(ctx: QuotedIdentifierContext): Unit = {
    replaceTokenByIdentifier(ctx, 1) {
      token =>
        // Remove the double back ticks in the string.
        token.setText(token.getText.replace("``", "`"))
        token
    }
  }

  /** Treats non-reserved keywords as Identifiers. */
  override def exitNonReserved(ctx: NonReservedContext): Unit = {
    replaceTokenByIdentifier(ctx, 0)(identity)
  }

  private def replaceTokenByIdentifier(ctx: ParserRuleContext, stripMargins: Int)(
      f: CommonToken => CommonToken = identity): Unit = {
    val parent = ctx.getParent
    parent.removeLastChild()
    val token = ctx.getChild(0).getPayload.asInstanceOf[Token]
    val newToken = new CommonToken(
      new org.antlr.v4.runtime.misc.Pair(token.getTokenSource, token.getInputStream),
      PaimonSqlExtensionsParser.IDENTIFIER,
      token.getChannel,
      token.getStartIndex + stripMargins,
      token.getStopIndex - stripMargins)
    parent.addChild(new TerminalNodeImpl(f(newToken)))
  }
}

/* Partially copied from Apache Spark's Parser to avoid dependency on Spark Internals */
case object PaimonParseErrorListener extends BaseErrorListener {
  override def syntaxError(
      recognizer: Recognizer[_, _],
      offendingSymbol: scala.Any,
      line: Int,
      charPositionInLine: Int,
      msg: String,
      e: RecognitionException): Unit = {
    val (start, stop) = offendingSymbol match {
      case token: CommonToken =>
        val start = Origin(Some(line), Some(token.getCharPositionInLine))
        val length = token.getStopIndex - token.getStartIndex + 1
        val stop = Origin(Some(line), Some(token.getCharPositionInLine + length))
        (start, stop)
      case _ =>
        val start = Origin(Some(line), Some(charPositionInLine))
        (start, start)
    }
    throw new PaimonParseException(None, msg, start, stop)
  }
}

/**
 * Copied from Apache Spark [[ParseException]], it contains fields and an extended error message
 * that make reporting and diagnosing errors easier.
 */
class PaimonParseException(
    val command: Option[String],
    message: String,
    start: Origin,
    stop: Origin)
  extends Exception {

  override def getMessage: String = {
    val builder = new StringBuilder
    builder ++= "\n" ++= message
    start match {
      case Origin(Some(l), Some(p), Some(_), Some(_), Some(_), Some(_), Some(_)) =>
        builder ++= s"(line $l, pos $p)\n"
        command.foreach {
          cmd =>
            val (above, below) = cmd.split("\n").splitAt(l)
            builder ++= "\n== SQL ==\n"
            above.foreach(builder ++= _ += '\n')
            builder ++= (0 until p).map(_ => "-").mkString("") ++= "^^^\n"
            below.foreach(builder ++= _ += '\n')
        }
      case _ =>
        command.foreach(cmd => builder ++= "\n== SQL ==\n" ++= cmd)
    }
    builder.toString
  }

  def withCommand(cmd: String): PaimonParseException =
    new PaimonParseException(Option(cmd), message, start, stop)
}

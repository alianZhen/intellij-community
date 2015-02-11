/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.vcs.log.graph.impl.print

import com.intellij.openapi.util.Pair
import com.intellij.util.NotNullFunction
import com.intellij.vcs.log.graph.AbstractTestWithTwoTextFile
import com.intellij.vcs.log.graph.*
import com.intellij.vcs.log.graph.api.GraphLayout
import com.intellij.vcs.log.graph.api.LinearGraph
import com.intellij.vcs.log.graph.api.elements.GraphEdge
import com.intellij.vcs.log.graph.api.elements.GraphElement
import com.intellij.vcs.log.graph.api.elements.GraphNode
import com.intellij.vcs.log.graph.api.printer.PrintElementManager
import com.intellij.vcs.log.graph.impl.permanent.GraphLayoutBuilder
import com.intellij.vcs.log.graph.impl.print.elements.PrintElementWithGraphElement
import com.intellij.vcs.log.graph.parser.LinearGraphParser
import com.intellij.vcs.log.graph.utils.LinearGraphUtils
import org.junit.Test

import java.io.IOException
import java.util.Comparator

import org.junit.Assert.assertEquals

public class PrintElementGeneratorTest : AbstractTestWithTwoTextFile("elementGenerator") {

  class TestPrintElementManager(private val myGraphElementComparator: Comparator<GraphElement>) : PrintElementManager {

    override fun isSelected(printElement: PrintElementWithGraphElement): Boolean {
      return false
    }

    override fun getColorId(element: GraphElement): Int {
      if (element is GraphNode) {
        return (element as GraphNode).getNodeIndex()
      }

      if (element is GraphEdge) {
        val edge = element as GraphEdge
        val normalEdge = LinearGraphUtils.asNormalEdge(edge)
        if (normalEdge != null) return normalEdge!!.first + normalEdge!!.second
        return LinearGraphUtils.getNotNullNodeIndex(edge)
      }

      throw IllegalStateException("Incorrect graph element type: " + element)
    }

    override fun getGraphElementComparator(): Comparator<GraphElement> {
      return myGraphElementComparator
    }
  }

  override fun runTest(`in`: String, out: String) {
    val graph = LinearGraphParser.parse(`in`)
    val graphLayout = GraphLayoutBuilder.build(graph, object : Comparator<Int> {
      override fun compare(o1: Int, o2: Int): Int {
        return o1.compareTo(o2)
      }
    })
    val graphElementComparator = GraphElementComparatorByLayoutIndex(object : NotNullFunction<Int, Int> {
      override fun `fun`(nodeIndex: Int?): Int {
        return graphLayout.getLayoutIndex(nodeIndex!!)
      }
    })
    val elementManager = TestPrintElementManager(graphElementComparator)
    val printElementGenerator = PrintElementGeneratorImpl(graph, elementManager, 7, 2, 10)
    val actual = printElementGenerator.asString(graph.nodesCount())
    assertEquals(out, actual)
  }

  Test
  throws(javaClass<IOException>())
  public fun oneNode() {
    doTest("oneNode")
  }

  Test
  throws(javaClass<IOException>())
  public fun manyNodes() {
    doTest("manyNodes")
  }

  Test
  throws(javaClass<IOException>())
  public fun longEdges() {
    doTest("longEdges")
  }

  Test
  throws(javaClass<IOException>())
  public fun specialElements() {
    doTest("specialElements")
  }
}

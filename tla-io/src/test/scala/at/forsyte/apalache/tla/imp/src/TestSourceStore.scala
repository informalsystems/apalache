package at.forsyte.apalache.tla.imp.src

import at.forsyte.apalache.tla.lir.TlaEx
import at.forsyte.apalache.tla.lir.convenience.tla
import at.forsyte.apalache.tla.lir.src.SourceRegion
import at.forsyte.apalache.tla.lir.UntypedPredefs._
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestSourceStore extends AnyFunSuite {
  test("basic add and find") {
    val store = new SourceStore()
    val ex: TlaEx = tla.int(1)
    val loc = SourceLocation("root", SourceRegion(1, 2, 3, 4))
    store.addRec(ex, loc)
    val foundLoc = store.find(ex.ID)
    assert(loc == foundLoc.get)
  }

  test("recursive add and find") {
    val store = new SourceStore()
    val int1: TlaEx = tla.int(1)
    val set: TlaEx = tla.enumSet(int1)
    val loc = SourceLocation("root", SourceRegion(1, 2, 3, 4))
    store.addRec(set, loc)
    val foundLoc = store.find(set.ID)
    assert(loc == foundLoc.get)
    val foundLoc2 = store.find(int1.ID)
    assert(loc == foundLoc2.get)
  }

  test("locations are not overwritten") {
    val store = new SourceStore()
    val int1: TlaEx = tla.int(1)
    val set: TlaEx = tla.enumSet(int1)
    val set2: TlaEx = tla.enumSet(set)
    val loc1 = SourceLocation("tada", SourceRegion(100, 200, 300, 400))
    store.addRec(int1, loc1)
    val loc2 = SourceLocation("root", SourceRegion(1, 2, 3, 4))
    store.addRec(set2, loc2)
    assert(loc2 == store.find(set2.ID).get)
    assert(loc2 == store.find(set.ID).get)
    assert(loc1 == store.find(int1.ID).get)
  }
}

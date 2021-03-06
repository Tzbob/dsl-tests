package mtfrp.html.lang

import hokko.core.Engine
import mtfrp.lang.ClientFRPLib
import scala.js.language.JSMaps
import scala.js.language.dom._

trait HtmlNodeBuilderLib extends ClientFRPLib with EventOps with JSMaps with ElementOps {
  trait HtmlNode
  trait HtmlNodeDiff

  def createElement(vnode: Rep[HtmlNode]): Rep[Element]
  def diff(prev: Rep[HtmlNode], current: Rep[HtmlNode]): Rep[HtmlNodeDiff]
  def patch(rootNode: Rep[Element], diff: Rep[HtmlNodeDiff]): Rep[Unit]

  def mkText(str: Rep[String]): Rep[HtmlNode]
  def mkNode(
    tagName: Rep[String],
    handlers: List[Handler],
    attributes: Attributes = defaultAttributes(),
    children: Rep[List[HtmlNode]] = defaultChildren
  ): Rep[HtmlNode]

  trait Value[T]
  case class RepConst[T](node: Rep[T]) extends Value[T]
  case class RepList[T](node: Rep[List[T]]) extends Value[T]
  implicit def repNode[T](node: Rep[T]): Value[T] = RepConst(node)
  implicit def repNodes[T](nodes: Rep[List[T]]): Value[T] = RepList(nodes)

  implicit def repStrToNode(s: Rep[String]): Value[Html] =
    fun((e: Rep[ScalaJs[Engine]]) => mkText(s))
  implicit def strToVal(s: String)(implicit ev: String => Rep[String]): Value[Html] =
    repStrToNode(s)

  implicit class AttrString(k: String) {
    def :=(v: Rep[String]): Rep[Attribute] = make_tuple2((k, v))
    def :=(v: String): Rep[Attribute] = make_tuple2((k, v))
  }

  implicit class RepAttrString(k: Rep[String]) {
    def :=(v: Rep[String]): Rep[Attribute] = make_tuple2((k, v))
    def :=(v: String): Rep[Attribute] = make_tuple2((k, v))
  }

  type Attribute = (String, String)

  def defaultAttributes() = JSMap[String, Any]()
  type Attributes = Rep[Map[String, Any]]

  trait Handler {
    val eventDef: EventDef
    val m: Manifest[eventDef.Type]
    val eventHandler: Rep[eventDef.Type => Unit]
  }

  object Handler {
    def apply(evtDef: EventDef)(evtHandler: Rep[evtDef.Type] => Rep[Unit])(implicit ma: Manifest[evtDef.Type]) =
      new Handler {
        // help the typechecker with a singleton type
        val eventDef: evtDef.type = evtDef
        val m = ma
        val eventHandler = fun(evtHandler)
      }
  }

  lazy val defaultChildren = List()
  type Children = Rep[List[Html]]

  private def handleEvent(ev: EventDef)(implicit m: Manifest[ev.Type]): (ClientEvent[ev.Type], Rep[ScalaJs[Engine]] => Handler) = {
    val source = EventRep.source[ev.Type]
    val mkHandler = (engine: Rep[ScalaJs[Engine]]) => Handler(ev) { occ =>
      val lst =
        ScalaJsRuntime.encodeListAsSeq(List(
          ScalaJsRuntime.encodeTup2(make_tuple2(source -> occ))
        ))
      engine.fire(lst)
    }
    (ClientEvent(source, ReplicationCore()), mkHandler)
  }

  class EventTargetBuilder(val tagName: Rep[String]) {
    def apply(): HtmlNodeBuilder = new HtmlNodeBuilder(tagName, scala.List.empty)
    def apply(ev1: EventDef)(implicit m: Manifest[ev1.Type]): (HtmlNodeBuilder, ClientEvent[ev1.Type]) = {
      val (evt, handler) = handleEvent(ev1)
      val handlers = handler +: Nil
      (new HtmlNodeBuilder(tagName, handlers), evt)
    }

    // ugly boilerplate...
    def apply(ev1: EventDef, ev2: EventDef)(implicit m1: Manifest[ev1.Type], m2: Manifest[ev2.Type]): (HtmlNodeBuilder, ClientEvent[ev1.Type], ClientEvent[ev2.Type]) = {
      val (evt1, handler1) = handleEvent(ev1)
      val (evt2, handler2) = handleEvent(ev2)

      val handlers = handler2 +: handler1 +: Nil
      (new HtmlNodeBuilder(tagName, handlers), evt1, evt2)
    }
    // ... 3,4,5..
  }

  private def vToRepList[T: Manifest](nvs: Seq[Value[T]]): Rep[List[T]] = {
    val vnodes = nvs.collect { case RepConst(n) => n }
    val vnodeLists = list_new(vnodes) +: nvs.collect { case RepList(ns) => ns }
    vnodeLists.foldLeft(List[T]())(_ ++ _)
  }

  type Html = ScalaJs[Engine] => HtmlNode

  class HtmlNodeBuilder(tagName: Rep[String], handlers: List[Rep[ScalaJs[Engine]] => Handler]) {
    def apply(attrs: Value[Attribute]*)(children: Value[Html]*): Rep[Html] =
      fun { e: Rep[ScalaJs[Engine]] =>
        val jsAttrs = vToRepList(attrs)
        val props = defaultAttributes()

        val repList = vToRepList(children)
        val htmlNodes = repList.map(_(e))

        jsAttrs.foreach { tuple => props.update(tuple._1, tuple._2) }
        mkNode(tagName, handlers.map(_(e)), props, htmlNodes)
      }
  }
}

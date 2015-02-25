package mtfrp.lang

import hokko.core.{ Event => HEvent, EventSource => HEventSource, Engine }
import java.net.URLEncoder
import java.util.UUID
import scala.js.language._
import scala.virtualization.lms.common._
import spray.http._
import spray.json._
import spray.routing._
import spray.routing.Directives._
import spray.routing.RequestContext

trait ReplicationCoreLib extends JSJsonFormatLib with EventSources
  with HEvent.EventLib with HEvent.EventStaticLib
  with Engine.EngineLib with Engine.EngineStaticLib
  with Engine.Pulses.PulsesLib
  with XMLHttpRequests with DelayedEval
  with JSMaps with ListOps with ListOps2 with TupleOps with OptionOps {

  case class Message(name: String, json: String) extends Adt
  val MessageRep = adt[Message]
  object Message extends DefaultJsonProtocol {
    implicit val messageFormat = jsonFormat2(Message.apply)
  }

  class ToClientDependency[T: JsonWriter: JSJsonReader: Manifest](
    exit: HEvent[Client => Option[T]],
    entry: Rep[HEventSource[T]]
  ) {
    val name = UUID.randomUUID.toString

    val messageCarrier: HEvent[Client => Option[Message]] =
      exit.map { fun =>
        (c: Client) => fun(c).map { t =>
          Message(name, t.toJson.compactPrint)
        }
      }

    val mkPulse: Rep[String => (HEventSource[T], T)] =
      fun { jsonPulse =>
        (entry, jsonPulse.convertToRep[T])
      }
  }

  class ToServerDependency[T: JsonReader: JSJsonWriter: Manifest](
    exit: Rep[HEvent[T]],
    entry: HEventSource[(Client, T)]
  ) {
    val name = UUID.randomUUID.toString

    val messageCarrier: Rep[HEvent[Message]] =
      exit.map(fun { (t: Rep[T]) =>
        MessageRep(unit(name), t.toJSONString)
      })

    def pulse(jsonPulse: String, clientId: String): (HEventSource[(Client, T)], (Client, T)) = {
      val newPulse = Client(clientId) -> jsonPulse.parseJson.convertTo[T]
      (entry, newPulse)
    }
  }

  object ReplicationCore {
    def empty = ReplicationCore()
    def merge(seq: Seq[ReplicationCore]) =
      seq.foldLeft(ReplicationCore.empty) { (acc, n) =>
        acc.+(n)
      }
  }

  case class ReplicationCore(
    toClientDeps: Set[ToClientDependency[_]] = Set.empty,
    toServerDeps: Set[ToServerDependency[_]] = Set.empty
  ) {
    def +(others: ReplicationCore*): ReplicationCore = {
      def fold[T](v: ReplicationCore => Set[T]) =
        others.foldLeft(v(this))(_ ++ v(_))
      val toClientDeps = fold(_.toClientDeps)
      val toServerDeps = fold(_.toServerDeps)
      ReplicationCore(toClientDeps, toServerDeps)
    }

    // merge the server exit nodes into 1 message carrier
    val serverCarrier =
      HEvent.merge(toClientDeps.map(_.messageCarrier).toSeq)
    // compile the FRP network (this has all the bottom nodes of the graph)
    val serverEngine = Engine.compile(serverCarrier)()

    // merge the client exit nodes into 1 message carrier
    val clientCarrier = {
      val carriers = toServerDeps.map(_.messageCarrier).toSeq
      EventRep.merge(List(carriers: _*))
    }
    // compile the FRP network (this has all the bottom nodes of the graph)
    val clientEngine = EngineRep.compile(List(clientCarrier))(List())

    def route: Option[Route] = {
      val r1 = initializeToClientDependencies()
      val r2 = initializeToServerDependencies()
      if (r1.isDefined)
        if (r2.isDefined) Some(r1.get ~ r2.get)
        else Some(r1.get)
      else None
    }

    /**
     * @return optionally, the Route that encompasses all involved client
     * functionality
     */
    def initializeToClientDependencies(): Option[Route] =
      if (!toClientDeps.isEmpty) {
        val url = URLEncoder.encode(UUID.randomUUID.toString, "UTF-8")
        initClientSideToClient(url)
        Some(initServerSideToClient(url))
      } else None

    /**
     *  @return optionally, the Route that encompasses all involved server
     *  functionality
     */
    def initializeToServerDependencies(): Option[Route] =
      if (!toServerDeps.isEmpty) {
        val url = URLEncoder.encode(UUID.randomUUID.toString, "UTF-8")
        initClientSideToServer(url)
        Some(initServerSideToServer(url))
      } else None

    /**
     * initialize the client section of 'toClient' calls
     *  i.e., listen to the server's updates and push values into the
     *  right EventSource's on the client
     */
    private def initClientSideToClient(url: String): Unit = {
      implicit def messageOps(m: Rep[Message]) = adtOps(m)

      val namedPulseMakers = {
        val map = JSMap[String, String => (HEventSource[T], T) forSome { type T }]()
        val namedToClientDeps = toClientDeps.map { d =>
          (d.name, d.mkPulse)
        }
        namedToClientDeps.foreach {
          case (name, entry) =>
            map.update(unit(name), entry)
        }
        map
      }

      val sseSource = EventSource(includeClientIdParam(url))
      sseSource.onmessage = fun { ev: Rep[Dataliteral] =>
        val messages = implicitly[JSJsonReader[List[Message]]].read(ev.data)
        val pulses = messages.map { (message: Rep[Message]) =>
          namedPulseMakers(message.name)(message.json)
        }
        clientEngine.fire(pulses)
      }
    }

    /**
     * initialize the server section of 'toClient' calls
     *  i.e., subscribe to the serverCarrier and push new values
     *  to the client using server sent events
     */
    private def initServerSideToClient(url: String): Route =
      path(url) {
        get {
          parameter('id) { id =>
            ctx =>
              val client = Client(id)
              val padding = s""": ${" " * 2049}\n"""
              val responseStart = HttpResponse(
                entity = HttpEntity(MediaType.custom("text/event-stream"), padding)
              )
              ctx.responder ! ChunkedResponseStart(responseStart)

              serverEngine.subscribeForPulses { pulses =>
                val pulse = pulses(serverCarrier)
                pulse.foreach { seq =>
                  val msgs = seq.map(_(client)).flatten
                  val data = s"data:${msgs.toJson.compactPrint}\n\n"
                  ctx.responder ! MessageChunk(data)
                }
              }
          }
        }
      }

    /**
     * initialize the client section of 'toServer' calls
     *  i.e., subscribe to the clientCarrier and push new values
     *  to the server using XMLHttpRequest
     */
    private def initClientSideToServer(url: String): Unit = {
      clientEngine.subscribeForPulses { pulses: Rep[Engine.Pulses] =>
        val pulse = pulses(clientCarrier)
        pulse.foreach { seq: Rep[Seq[Message]] =>
          val req = XMLHttpRequest()
          req.open(unit("POST"), includeClientIdParam(url))
          req.send(seq.toJSONString)
        }
      }
    }

    /**
     * initialize the server section of 'toServer' calls
     *  i.e., listen to POST requests from clients
     *  and push new values to the server EventSources
     */
    private def initServerSideToServer(url: String): Route = {
      val namedToServerDeps = toServerDeps.map { d =>
        (d.name, d)
      }.toMap[String, ToServerDependency[_]]

      path(url) {
        parameter('id) { id =>
          post {
            entity(as[String]) { data =>
              complete {
                val messages = data.parseJson.convertTo[List[Message]]
                val pulses = messages.map { message =>
                  namedToServerDeps(message.name).pulse(message.json, id)
                }
                serverEngine.fire(pulses)
                "OK"
              }
            }
          }
        }
      }
    }
  }
}

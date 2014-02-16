package mtfrp.gen

import java.io.{ PrintWriter, StringWriter }
import java.net.URLEncoder
import java.util.UUID

import scala.xml.Unparsed

import mtfrp.exp.MtFrpProgExp
import spray.http.{ HttpCookie, MediaTypes }
import spray.httpx.marshalling.ToResponseMarshallable.isMarshallable
import spray.routing.Directive.pimpApply
import spray.routing.{ Route, Directives }

object PageCompiler {
  import Directives._

  def makeRoute(prog: MtFrpProgExp)(url: String): Route = {
    lazy val signal = prog.mainGen
    val gen = new GenMtFrp { val IR: prog.type = prog }
    val block = gen.reifyBlock(signal.rep)

    /*
     * TODO
     *  generate frpID for setCookie
     */

    def html = {
      val sw = new StringWriter
      val out = new PrintWriter(sw)
      gen.emitSource(Nil, block, "", out)
      val js = sw.toString

      <html>
        <head>
          <title>Chat</title>
          <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
          <!-- Loading Bootstrap -->
          <link href="bootstrap/css/bootstrap.css" rel="stylesheet"/>
          <!-- Loading Flat UI -->
          <link href="css/flat-ui.css" rel="stylesheet"/>
          <link rel="shortcut icon" href="images/favicon.ico"/>
        </head>
        <body>
          <script src="//cdnjs.cloudflare.com/ajax/libs/bacon.js/0.7.2/bacon.min.js"></script>
          <script type="text/javascript">({ Unparsed(js) })()</script>
        </body>
      </html>
    }

    val pageRoute = path(url) {
      get {
        setCookie(HttpCookie("frpID", content = URLEncoder encode (UUID.randomUUID.toString, "UTF-8"))) {
          respondWithMediaType(MediaTypes.`text/html`)(complete(html))
        }
      }
    }

    signal.route match {
      case Some(route) => pageRoute ~ route
      case None        => pageRoute
    }
  }

}
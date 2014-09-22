import Geometry.Point
import model.{Game, World}

import scala.collection.mutable.ListBuffer

object DangerArea {

  def targetPoint(world: World, game: Game, gap: Int = 5) = {
    val xnet = game.goalNetWidth
    val ynet = game.goalNetTop
    val puckR = world.puck.radius
    val targetX = xnet
    val targetY = ynet + puckR + gap
    new Point(targetX, targetY)
  }

  def safetyPad = 3

  def calculatePoints(world: World, game: Game, step: Int = 15, puckSpeed: Double = 16, gap: Int = 5) = {
    val points = new ListBuffer[Point]
    val xnet = game.goalNetWidth
    val ynet = game.goalNetTop
    val netH = game.goalNetHeight
    val goalR = world.hockeyists.headOption.map(_.radius).getOrElse(30.0)
    val puckR = world.puck.radius
    val goalV = game.goalieMaxSpeed

    val endX = game.worldWidth * 3 / 2
    val endY = game.rinkBottom

    val targetX = xnet
    val targetY = ynet + puckR + gap

    def isDanger(x: Double, y: Double):Boolean = {
      val x_before_g = xnet + goalR
      val y_before_g = targetY + goalR / (x - targetX) * (y - targetY)
      val t_for_puck = Math.hypot(x_before_g - x, y_before_g - y) / puckSpeed
      val goalie_min_y = ynet + netH - goalR
      val goalie_y = Math.min(y, goalie_min_y)
      val goalie_t =
        if (y <= goalie_min_y) {
          t_for_puck
        }
        else {
          t_for_puck * (goalie_min_y - y_before_g) / (y - y_before_g)
        }

      val goalie_y_after_t = goalie_y - goalV*goalie_t
      val goalie_top = goalie_y_after_t - goalR
      val puck_bottom = y_before_g + puckR + safetyPad
      puck_bottom <= goalie_top
    }

    for (x <- (xnet + goalR*2) to (endX, step)) {
      for (y <- ynet to (endY, step)) {
        if (isDanger(x, y)) points += new Point(x,y)
      }
    }

    points.toArray
  }


}

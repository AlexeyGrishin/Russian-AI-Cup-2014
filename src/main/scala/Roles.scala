import Geometry.Point
import model.ActionType.{Pass, TakePuck, Strike}
import model.{Unit => ModelUnit, Hockeyist, World, Game, Move, Puck}

import WorldEx._

/**
 * Содержит "роли" - функции, которые выполняют хокеисты.
 * Каждому хокеисту назначается такая роль и вызывается на каждом тике
 * См Trainer - он назначает роли
 */
object Roles {

  def puckWillGoToOurNetAfterStrike(self: Hockeyist) = {
    puckWillGoToOurNet(self.lookVector)
  }

  def puckWillGoToOurNet(vec: Geometry.Vector) = {
    val col = Physics.getCollisionWithWall(world.puck, vec)
    col != null && col.inNet && col.onOurSide
  }

  def puckWillGoToEnemyNet(vec: Geometry.Vector) = {
    val col = Physics.getCollisionWithWall(world.puck, vec)
    col != null && col.inNet && col.onEnemySide
  }

  object DoNothing extends Role {
    override def move(self: Hockeyist, world: World, game: Game, move: Move): Unit = {
      //nothing
    }
  }

  //Защита. Алгоритм простой: держимся в районе ворот по центру - почти всегда шайба летит там.
  //Если шайба летит, смотрим куда - если в ворота, то движемся/поворачиваемся к ней и страйкаем
  //Если явно мимо - забить болт
  //Если есть возможность взять и нет опасности - пытаемся взять
  //Если взяли - пасуем подальше от врагов
  object DoDefence extends Role {
    override def move(self: Hockeyist, world: World, game: Game, move: Move): Unit = {
      self.targetPoints = List()
      lastStatus = ""
      val net = WorldEx.myZone.net
      val enemyWithPuck = world.hockeyists.find(h => h.isMoveableEnemy && h.ownPuck)
      if (self.ownPuck) {
        var freePoint = Passer.findIdealForPass(self)
        Passer.passTarget = freePoint
        self.targetPoints = List(freePoint)
        lastStatus += "pass puck to area free of enemies"
        Passer.doPass(self, move)
        return
      }


      if (!isPuckOwnedByOur && !enemyWithPuck.isDefined && puckWillGoToOurNet(world.puck.velocityVector) && world.puck.velocity > 14) {
        lastStatus += "puck is running to net"
        if (!strikeOrTakeIfCan(self, move)) {
          val rp = Mover.estimatedRandevousPoint(self, world.puck)
          if (rp.onOurSide) {
            lastStatus += "\ngo to it"
            self.passFrom = null //TODO: иначе arriveForNet может продолжать думать будто мы все еще идем к точке
            //TODO: если идет сквозь меня - просто развернуться
            Mover.arriveFor(self, world.puck, move, limit = 3*game.stickLength)
            return
          }
          else {
            lastStatus += "\nis on enemy side, wait"
            Mover.doTurn(self, world.puck, move, self.passFrom)
            return
          }
        }
        else {
          return
        }
      }
      if (!isPuckOwnedByOur && takeIfCan(self, move)) {
        return
      }
      if ((!isPuckOwnedByEnemy || world.puck.distanceTo(self) > game.worldWidth/2) && strikeEnemyIfCan(self, move)) {
        return
      }

      //TODO: for puck velocity vector == owner's lookvector
      val movingTarget = world.puck//TODO: Physics.targetAfter(enemyWithPuck.getOrElse(world.puck), 40)
      lastStatus += "\n"

      val puckIsSafe = isPuckOwnedByOur || world.puck.onEnemySide
      val nearMiddle = self.distanceTo(WorldEx.myZone.net.middle) < 30


      if (net.includes(self) && (nearMiddle || !puckIsSafe)) {
        Mover.doTurn(self, movingTarget, move, self.passFrom)
        lastStatus += "look for puck"
      }
      else {
        lastStatus += "go to net"
        Mover.arriveToNetAndStop(self, move) {
          Mover.doTurn(self, movingTarget, move, self.passFrom)
        }
      }
      self.targetPoints = movingTarget :: self.targetPoints
    }
  }

  //Просто шмаляем во вражеские ворота. Юзается для овертайма когда вратарей нет
  object StrikeToNet extends Role {
    override def move(self: Hockeyist, world: World, game: Game, move: Move): Unit = {
      //overtime - просто едем к воротам и лупим
      if (puckWillGoToEnemyNet(self.lookVector)) {
        lastStatus = "strike to enemy net"
        move.action = Strike
      }
      else {
        lastStatus = "turn to enemy net"
        Mover.doMove2(self, self -> WorldEx.enemyZone.net.middle, move)
      }
    }
  }

  //Пытается перехватить врага с шайбой который едет забивать гол
  //В идеале должен был учитывать взаимное положение и ехать реально на перехват в зону удара
  //По факту - едет следом, хорошо если успевает выбить
  object PreventStrike extends Role {
    override def move(self: Hockeyist, world: World, game: Game, move: Move): Unit = {
      if (strikeOrTakeIfCan(self, move)) {
        return
      }
      val enemyWithPuck = world.hockeyists.find(h => h.isMoveableEnemy && h.ownPuck).orNull
      if (enemyWithPuck == null) {
        //strange...
        LookupForPuck.move(self, world, game, move)
        return
      }

      //val pointAfterSomeTime = Physics.targetAfter(enemyWithPuck, 250, acceleration = true)
      //enemyWithPuck.targetPoints = List(pointAfterSomeTime)
      val enemyIsGoingToUs = WorldEx.myZone.half.includes(enemyWithPuck) || (enemyWithPuck.velocity > 1 && Math.signum(enemyWithPuck.speedX) == WorldEx.myZone.dx /*&& WorldEx.myZone.half.includes(pointAfterSomeTime)*/)
      if (enemyIsGoingToUs) {
        lastStatus = "enemy is going to us. intersect it\n"
        val nearestDangerZonePoint = WorldEx.myZone.danger(22).nearestTo(enemyWithPuck).toList.sortBy(p => enemyWithPuck.velocityVector normal_* (enemyWithPuck -> p)).reverse.head
        lastStatus += s"we are: ${Physics.positionRelativeOf(self, enemyWithPuck, nearestDangerZonePoint)}\n"
        enemyWithPuck.targetPoints = nearestDangerZonePoint :: self.targetPoints
        if (WorldEx.myZone.danger(22).includes(self) || WorldEx.myZone.danger(22).includes(enemyWithPuck) || WorldEx.myZone.danger(22).includes(world.puck) || nearestDangerZonePoint.distanceTo(enemyWithPuck) < 50) {
          lastStatus += "enemy is here\n"
          KickAsses.move(self, world, game, move)
          lastStatus += KickAsses.lastStatus
          return
        }
        Mover.doMove2(self, self -> nearestDangerZonePoint, move)

      }
      else {
        lastStatus = "enemy is not going to us\n"
        LookupForPuck.move(self, world, game, move)
        lastStatus += LookupForPuck.lastStatus
      }
    }
  }

  //Отладочная роль :)
  object FoolingAround extends Role {
    val pointsToReach = List(WorldEx.enemyZone.net.middle, WorldEx.myZone.net.middle)
    var point: Point = null
    override def move(self: Hockeyist, world: World, game: Game, move: Move): Unit = {
      self.targetVectors = List()
      self.targetPoints = List()
      if (self.ownPuck) {
        if (point == null || point.distanceTo(self) <= 40) {
          point = pointsToReach.filter(p => p != point).toArray.apply((Math.random()*(pointsToReach.length-1)).toInt)
        }
        Mover.doMove3(self, point, (self.point->point)(100), move, predictCollisionsWeight = 100, turnOutPuck = false)
        //move.action = Strike
      }
      else if (self.canOwnPuck) {
        move.action = TakePuck
      }
      else {
        LookupForPuck.move(self, world, game, move)
      }
    }
  }

  //Едет за шайбой. Пытается предсказать ее перемещение и перехватить.
  //Если шайба у союзника - ожидает пас и едет в ту же точку.
  //Если шайба у врага - выбивает если это неопасно
  object LookupForPuck extends Role {
    override def move(self: Hockeyist, world: World, game: Game, move: Move): Unit = {
      if (isPuckOwnedByOur) {
        //do nothing just wait
        lastStatus = s"owned by us, going to pass it to ${Passer.passTarget}"
        Mover.doMove2(self, self.point -> Option(Passer.passTarget).getOrElse(Passer.findIdealForPass(puckOwner.get)), move)
        return
      }
      if (isPuckOwnedByEnemy && self.canOwnPuck) {
        lastStatus = "owned by enemy and can take/strike\n"
        val strikeIsSafe = {
          if (puckWillGoToOurNetAfterStrike(self)) {
            lastStatus += "do not strike to our net\n"
            false
          }
          else {
            lastStatus += "strike is safe\n"
            true
          }
        }
        move.action = if (strikeIsSafe) Strike else TakePuck
      }
      else if (!isPuckOwnedByEnemy && self.canOwnPuck) {
        lastStatus = "just take it"
        move.action = TakePuck
      }
      else if (isPuckOwnedByEnemy) {
        lastStatus = "go to puck in enemy hands"
        Mover.arriveFor(self, world.puck/*world.hockeyists.find(h => h.isMoveableEnemy && h.ownPuck).get*/, move)
      }
      else  {
        lastStatus = "go to puck\n"
        world.hockeyists.filter(_.isMoveableEnemy).foreach(h => {
          lastStatus += s"${h.id} is: ${Physics.positionRelativeOf(h, self, (self.velocityVector*10)(self))}\n"
          lastStatus += s"${h.id} will take puck: ${Physics.timeToArrivalForStick(h, world.puck)}\n"
        })
        Mover.arriveFor(self, world.puck, move)
      }
    }

  }

  //Лупить всех клюшкой :)
  object KickAsses extends Role {
    override def move(self: Hockeyist, world: World, game: Game, move: Move): Unit = {
      val enemyClosestToPuck = world.hockeyists.filter(_.isMoveableEnemy).sortBy(_.distanceTo(world.puck)).head
      if (self.canPunch(enemyClosestToPuck)) {
        lastStatus = "can strike enemy\n"
        val strikeIsSafe = !self.canOwnPuck || {
          if (puckWillGoToOurNetAfterStrike(self)) {
            lastStatus += "do not strike to our net\n"
            false
          }
          else {
            lastStatus += "strike is safe\n"
            true
          }
        }
        if (strikeIsSafe) {
          lastStatus += "do strike\n"
          move.action = Strike
          return
        }
      }
      lastStatus = ""
      //else
      if (self.remainingCooldownTicks == 0 || !isPuckOwnedByOur) {
        Mover.doMove2(self, self.point->enemyClosestToPuck.point, move)
      }
      else {
        lastStatus += "\ncooldown - go to our friend"
        Mover.doMove2(self, self.point->puckOwner.get.point, move)
      }
      //Mover.arriveFor(self, enemyClosestToPuck, move)
    }
  }


  //Едет через зоны вдоль корта к "опасной" зоне. Там - см Mover.arriveToStrike2
  object MakeGoal2 extends Role {
    override def move(self: Hockeyist, world: World, game: Game, move: Move): Unit = {

      val inCentralZone = self >> WorldEx.myZone.start && Math.abs(self.y - Geometry.middleY) <  self.radius*4
      val inZone0 = self << WorldEx.myZone.speedupZone1Bottom
      val inZone1 = !inZone0 && self << WorldEx.myZone.speedupZone2Bottom
      val inEnemyCorner = self >> WorldEx.enemyZone.net
      val inEnemyDangerZone = !inZone0 && !inZone1 && !inEnemyCorner

      val looksToOurNet = self.velocityVector.matchesDx(WorldEx.myZone.dx)

      val expectedZone = self.nextZone
      lastStatus = ""
      val enemiesThatCouldBother = world.hockeyists.filter(_.isMoveableEnemy).filter(_.remainingCooldownTicks == 0)
      val enemiesArrivalToPuck = (
        enemiesThatCouldBother.map(e => Physics.timeToArrivalForStick_movingTarget(e, world.puck, self))
          ++ List(999.0)
        ).min

      val passTarget = Passer.findIdealForPass(self)
      val passTurnTime = Passer.timeBeforePass(self, passTarget)

      if (expectedZone == null || expectedZone.includes(self)) {
        val zoneName = if (inCentralZone) "center" else if (inZone0) "zone0" else if (inZone1) "zone1" else if (inEnemyCorner) "enemy corner" else "enemy danger"
        lastStatus = s"in ${zoneName}\n"

        self.nextZone = if (inCentralZone) {
          Mover.doMoveThroughZone(self, WorldEx.myZone.speedupZone1Top + WorldEx.myZone.speedupZone2Top, move, true, appender)
          //Mover.doMoveThroughZone(self, WorldEx.myZone.start, move, false, appender)
        }
        else if (inZone0) {
          Mover.doMoveThroughZone(self, WorldEx.myZone.speedupZone1Top, move, true, appender)
        }
        else if (inZone1/* && !looksToOurNet*/) {
          //Mover.doMoveThroughZone(self, if (world.puck.isTop) WorldEx.myZone.speedupZone2Top else WorldEx.myZone.speedupZone2Bottom, move, false, appender)
          Mover.doMoveThroughZone(self, WorldEx.myZone.speedupZone2Bottom, move, true, appender)
        }
        /*else if (inZone1 && looksToOurNet) {
          Mover.doMoveThroughZone(self, WorldEx.myZone.start, move, false, appender)
        }*/
        else if (inEnemyDangerZone && !looksToOurNet) {
          Mover.arriveToStrike2(self, move, appender) match {
            case Mover.Arrived =>
              move.action = Strike
              null
            case Mover.OnWay =>
              //do nothing
              null
            case Mover.Canceled =>
              //pass to defencer
              Passer.doPass(self, move)
              null
          }

        }
        else if ((inEnemyDangerZone && looksToOurNet) || inEnemyCorner) {
          Mover.doMoveThroughZone(self, WorldEx.myZone.speedupZone1Top, move, true, appender)
        }
        else {
          //?
          lastStatus += "well, strange...\n"
          Mover.doMoveThroughZone(self, WorldEx.myZone.start, move, false, appender)
        }


      }
      else {
        lastStatus += "continue moving to zone\n"
        Mover.doMoveThroughZone(self, expectedZone, move, false, appender)
      }

      if (self.nextZone != null) {
        //we are moving. check enemies arrival
        lastStatus += s"still moving. check time (ea = $enemiesArrivalToPuck, pt = $passTurnTime)\n"
        if (enemiesArrivalToPuck - passTurnTime < 10 && self.remainingCooldownTicks < enemiesArrivalToPuck) {
          lastStatus += "time to pass...but i will not :)\n"
          //Passer.doPass(self, move)
        }


      }

    }
  }



}

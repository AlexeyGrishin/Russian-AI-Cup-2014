import model.{Move, Game, World, Hockeyist}

trait Role {
  def move(self: Hockeyist, world: World, game: Game, move: Move): Unit

  def name: String = this.getClass.getName

  var lastStatus: String = ""
}
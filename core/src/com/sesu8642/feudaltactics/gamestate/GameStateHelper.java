package com.sesu8642.feudaltactics.gamestate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.reflect.ClassReflection;
import com.sesu8642.feudaltactics.gamestate.mapobjects.Capital;
import com.sesu8642.feudaltactics.gamestate.mapobjects.Castle;
import com.sesu8642.feudaltactics.gamestate.mapobjects.MapObject;
import com.sesu8642.feudaltactics.gamestate.mapobjects.Tree;
import com.sesu8642.feudaltactics.gamestate.mapobjects.Unit;
import com.sesu8642.feudaltactics.gamestate.mapobjects.Unit.UnitTypes;
import com.sesu8642.feudaltactics.input.InputValidationHelper;

public class GameStateHelper {
	// only class supposed to modify the game state (except the bot AI actually)

	public final static float TREE_SPREAD_RATE = 0.3F;
	public final static float TREE_SPAWN_RATE = 0.01F;
	public final static float DEAFULT_INITIAL_TREE_DENSITY = 0.1F;
	public final static float WIN_LANDMASS_PERCENTAGE = 0.8F;

	// prevent instanciation
	private GameStateHelper() {
		throw new AssertionError();
	}
	
	public static void initializeMap(GameState gameState, ArrayList<Player> players, float landMass, float density,
			Float vegetationDensity, Long mapSeed) {
		if (mapSeed == null) {
			mapSeed = System.currentTimeMillis();
		}
		if (vegetationDensity == null) {
			vegetationDensity = DEAFULT_INITIAL_TREE_DENSITY;
		}
		gameState.setSeed(mapSeed);
		gameState.setPlayers(players);
		gameState.setMap(new HexMap());
		gameState.setKingdoms(new ArrayList<Kingdom>());
		if (landMass == 0) {
			return;
		}
		generateMap(gameState, players, landMass, density, vegetationDensity, mapSeed);
	}

	public static void generateMap(GameState gameState, ArrayList<Player> players, float landMass, float density,
			float vegetationDensity, Long mapSeed) {
		// if not every player has at least one kingdom, try again
		do {
			generateTiles(gameState, players, landMass, density, mapSeed);
			createInitialKingdoms(gameState);
			// generate a new seed from the seed
			gameState.getRandom().setSeed(mapSeed);
			mapSeed = gameState.getRandom().nextLong();
		} while (!doesEveryPlayerHaveAKingdom(gameState));
		createTrees(gameState, vegetationDensity);
		createCapitals(gameState);
		sortPlayersByIncome(gameState);
		createMoney(gameState);
	}

	private static boolean doesEveryPlayerHaveAKingdom(GameState gameState) {
		List<Player> playersWithoutKingdoms = new ArrayList<Player>(gameState.getPlayers());
		for (Kingdom kingdom : gameState.getKingdoms()) {
			if (playersWithoutKingdoms.contains(kingdom.getPlayer())) {
				playersWithoutKingdoms.remove(kingdom.getPlayer());
			}
		}
		if (playersWithoutKingdoms.isEmpty()) {
			return true;
		} else {
			return false;
		}
	}

	private static void sortPlayersByIncome(GameState gameState) {
		gameState.getPlayers().sort((a, b) -> {
			// if they are the same, it doesn't matter
			int incomeA = gameState.getKingdoms().stream().filter(kingdom -> kingdom.getPlayer() == a)
					.mapToInt(kingdom -> kingdom.getIncome()).sum();
			int incomeB = gameState.getKingdoms().stream().filter(kingdom -> kingdom.getPlayer() == b)
					.mapToInt(kingdom -> kingdom.getIncome()).sum();
			return incomeA > incomeB ? 1 : -1;
		});
	}

	private static void generateTiles(GameState gameState, ArrayList<Player> players, float landMass, float density,
			Long mapSeed) {
		// density between -3 and 3 produces good results
		// set seed
		gameState.getRandom().setSeed(mapSeed);
		// distribute the land mass evenly to all players
		Map<Player, Integer> tileAmountsToGenerate = new HashMap<Player, Integer>();
		// if there are tiles left, distribute them to random players
		Collections.shuffle(players, gameState.getRandom());
		int remainingLandMass = (int) (landMass % players.size());
		for (Player player : players) {
			int additionalTiles = 0;
			if (remainingLandMass > 0) {
				additionalTiles = 1;
				remainingLandMass--;
			}
			tileAmountsToGenerate.put(player, (int) (landMass / players.size() + additionalTiles));
		}
		// keep track of the players that still have tiles left to generate in a list
		// (because a random one can be selected)
		ArrayList<Player> remainingPlayers = new ArrayList<Player>(players);
		gameState.getMap().getTiles().clear();
		// could be done recursively but stack size is uncertain
		Vector2 nextTilePos = new Vector2(0, 0);
		ArrayList<Vector2> positionHistory = new ArrayList<Vector2>(); // for backtracking
		while (remainingPlayers.size() > 0) {
			Vector2 currentTilePos = nextTilePos;
			// place tile
			Player player = remainingPlayers.get(gameState.getRandom().nextInt(remainingPlayers.size()));
			HexTile tile = new HexTile(player, currentTilePos);
			gameState.getMap().getTiles().put(currentTilePos, tile);
			// remove player if no tiles are left
			if (tileAmountsToGenerate.get(player) == 1) {
				remainingPlayers.remove(player);
			} else {
				tileAmountsToGenerate.put(player, tileAmountsToGenerate.get(player) - 1);
			}
			// add to history
			positionHistory.add(currentTilePos);
			// get next tile position with empty neighboring tiles
			ArrayList<Vector2> usableCoords = gameState.getMap().getUnusedNeighborCoords(currentTilePos);
			while (usableCoords.isEmpty()) {
				// backtrack until able to place a tile again
				positionHistory.remove(positionHistory.size() - 1);
				currentTilePos = positionHistory.get(positionHistory.size() - 1);
				usableCoords = new ArrayList<Vector2>(gameState.getMap().getUnusedNeighborCoords(currentTilePos));
			}
			// calculate a score for each neighboring tile for choosing the next one
			ArrayList<Float> scores = new ArrayList<Float>();
			float scoreSum = 0;
			for (Vector2 candidate : usableCoords) {
				// factor in density
				int usableCoordsCountFromCandidate = gameState.getMap().getUnusedNeighborCoords(candidate).size();
				float score = (float) Math.pow(usableCoordsCountFromCandidate, density);
				scores.add(score);
				scoreSum += score;
			}
			// select tile based on score and random
			float randomScore = gameState.getRandom().nextFloat() * scoreSum;
			int index = 0;
			float countedScore = scores.get(0);
			while (countedScore < randomScore) {
				index++;
				countedScore += scores.get(index);
			}
			nextTilePos = usableCoords.get(index);
		}
	}

	private static void createInitialKingdoms(GameState gameState) {
		gameState.getKingdoms().clear();
		for (Entry<Vector2, HexTile> tileEntry : gameState.getMap().getTiles().entrySet()) {
			HexTile tile = tileEntry.getValue();
			tile.setKingdom(null);
			for (HexTile neighborTile : gameState.getMap().getNeighborTiles(tile)) {
				if (neighborTile == null) {
					// water
					continue;
				}
				if (neighborTile.getPlayer() != tile.getPlayer()) {
					continue;
				}
				// two neighboring tiles belong to the same player
				if (tile.getKingdom() == null && neighborTile.getKingdom() == null) {
					// none of the tiles already belong to a kingdom --> create a new one
					Kingdom newKingdom = new Kingdom(tile.getPlayer());
					gameState.getKingdoms().add(newKingdom);
					newKingdom.getTiles().add(tile);
					newKingdom.getTiles().add(neighborTile);
					tile.setKingdom(newKingdom);
					neighborTile.setKingdom(newKingdom);
				} else if (tile.getKingdom() != null && neighborTile.getKingdom() == null) {
					// tile belongs to a kingdom but neighbor does not -> add neighbor to existing
					// kingdom
					tile.getKingdom().getTiles().add(neighborTile);
					neighborTile.setKingdom(tile.getKingdom());
				} else if (tile.getKingdom() == null && neighborTile.getKingdom() != null) {
					// neighbor belongs to a kingdom but tile does not -> add tile to existing
					// kingdom
					neighborTile.getKingdom().getTiles().add(tile);
					tile.setKingdom(neighborTile.getKingdom());
				} else if (tile.getKingdom() != null && neighborTile.getKingdom() != null
						&& tile.getKingdom() != neighborTile.getKingdom()) {
					// tile and neighbor belong to different kingdoms --> merge kingdoms
					gameState.getKingdoms().remove(neighborTile.getKingdom());
					for (HexTile neighborKingdomTile : neighborTile.getKingdom().getTiles()) {
						neighborKingdomTile.setKingdom(tile.getKingdom());
						tile.getKingdom().getTiles().add(neighborKingdomTile);
					}
				}
			}
		}
	}

	private static void createCapitals(GameState gameState) {
		for (Kingdom kingdom : gameState.getKingdoms()) {
			createCapital(gameState, kingdom);
		}
	}

	private static void createMoney(GameState gameState) {
		for (Kingdom kingdom : gameState.getKingdoms()) {
			int savings = kingdom.getTiles().size() * 5;
			// players other than the first one will earn some money once their turn starts
			if (gameState.getActivePlayer() != kingdom.getPlayer()) {
				savings -= kingdom.getIncome();
			}
			kingdom.setSavings(savings);
		}
	}

	private static void createTrees(GameState gameState, float vegetationDensity) {
		for (HexTile tile : gameState.getMap().getTiles().values()) {
			if (gameState.getRandom().nextFloat() <= vegetationDensity) {
				tile.setContent(new Tree());
			}
		}
	}

	private static void createCapital(GameState gameState, HexTile oldCapitalTile) {
		HexTile newCapitalTile;
		// try to find empty neighbor tile
		ArrayList<HexTile> neighborTiles = gameState.getMap().getNeighborTiles(oldCapitalTile);
		Optional<HexTile> optionalCapitalTile = neighborTiles.stream()
				.filter((HexTile neighborTile) -> neighborTile != null
						&& neighborTile.getKingdom() == oldCapitalTile.getKingdom()
						&& neighborTile.getContent() == null)
				.findFirst();
		if (optionalCapitalTile.isPresent()) {
			newCapitalTile = optionalCapitalTile.get();
		} else {
			// no empty neighbor tile -> select any empty tile in the kingdom
			optionalCapitalTile = oldCapitalTile.getKingdom().getTiles().stream()
					.filter((HexTile kingdomTile) -> kingdomTile.getContent() == null).findFirst();
			if (optionalCapitalTile.isPresent()) {
				newCapitalTile = optionalCapitalTile.get();
			} else {
				// no empty tile -> select any neighbor tile
				optionalCapitalTile = neighborTiles.stream().filter((HexTile neighborTile) -> {
					if (neighborTile != null && neighborTile.getKingdom() == oldCapitalTile.getKingdom()) {
						// make sure the tile is not unconnected
						if (neighborTile.getCachedNeighborTiles().stream()
								.anyMatch((HexTile neighborsNeighbor) -> neighborsNeighbor != null
										&& neighborsNeighbor != oldCapitalTile
										&& neighborsNeighbor.getKingdom() == oldCapitalTile.getKingdom())) {
							return true;
						}
					}
					return false;
				}).findFirst();
			}

			if (optionalCapitalTile.isPresent()) {
				newCapitalTile = optionalCapitalTile.get();
			} else {
				return;
			}
		}
		newCapitalTile.setContent(new Capital());
	}

	private static void createCapital(GameState gameState, Kingdom kingdom) {
		HexTile newCapitalTile;
		// try to find any empty kingdom tile
		Optional<HexTile> optionalCapitalTile = kingdom.getTiles().stream()
				.filter((HexTile kingdomTile) -> kingdomTile.getContent() == null).findFirst();
		if (optionalCapitalTile.isPresent()) {
			newCapitalTile = optionalCapitalTile.get();
		} else {
			// no empty tile -> just select any
			newCapitalTile = kingdom.getTiles().stream().findFirst().get();
		}
		newCapitalTile.setContent(new Capital());
	}

	public static void activateKingdom(GameState gameState, Kingdom kingdom) {
		kingdom.setWasActiveInCurrentTurn(true);
		gameState.setActiveKingdom(kingdom);
	}

	public static void pickupObject(GameState gameState, HexTile tile) {
		gameState.setHeldObject(tile.getContent());
		tile.setContent(null);
	}

	public static void placeOwn(GameState gameState, HexTile tile) {
		// units can't act after removing trees
		if (tile.getContent() != null && ClassReflection.isAssignableFrom(tile.getContent().getClass(), Tree.class)) {
			((Unit) gameState.getHeldObject()).setCanAct(false);
		}
		placeObject(gameState, tile);
	}

	public static void combineUnits(GameState gameState, HexTile tile) {
		// place resulting unit as held object
		// the unit that is not the peasant will be upgraded
		Unit oldUnit;
		if (((Unit) tile.getContent()).getUnitType() == UnitTypes.PEASANT) {
			oldUnit = (Unit) gameState.getHeldObject();
		} else {
			oldUnit = (Unit) tile.getContent();
		}
		UnitTypes newUnitType = null;
		switch (oldUnit.getUnitType()) {
		case PEASANT:
			newUnitType = UnitTypes.SPEARMAN;
			break;
		case SPEARMAN:
			newUnitType = UnitTypes.KNIGHT;
			break;
		case KNIGHT:
			newUnitType = UnitTypes.BARON;
			break;
		default:
			break;
		}
		Unit newUnit = new Unit(newUnitType);
		newUnit.setCanAct(((Unit) tile.getContent()).isCanAct());
		gameState.setHeldObject(newUnit);
		placeObject(gameState, tile);
	}

	public static void conquer(GameState gameState, HexTile tile) {
		ArrayList<HexTile> neighborTiles = gameState.getMap().getNeighborTiles(tile);
		Kingdom oldTileKingdom = tile.getKingdom();
		// units can't act after conquering
		((Unit) gameState.getHeldObject()).setCanAct(false);

		// update kingdoms
		if (tile.getKingdom() != null) {
			// place new capital if old one is going to be destroyed
			if (tile.getContent() != null
					&& ClassReflection.isAssignableFrom(tile.getContent().getClass(), Capital.class)
					&& tile.getKingdom().getTiles().size() > 2) {
				tile.getKingdom().setSavings(0);
				createCapital(gameState, tile);
			}
			tile.getKingdom().getTiles().remove(tile);
		}
		tile.setKingdom(gameState.getActiveKingdom());
		tile.getKingdom().getTiles().add(tile);
		ArrayList<HexTile> oldKingdomNeighborTiles = new ArrayList<HexTile>();
		for (HexTile neighborTile : neighborTiles) {
			if (neighborTile == null) {
				// water
				continue;
			}
			if (neighborTile.getKingdom() == null) {
				if (neighborTile.getPlayer() == tile.getPlayer()) {
					// connect tile without kingdom to kingdom
					neighborTile.setKingdom(tile.getKingdom());
					tile.getKingdom().getTiles().add(neighborTile);
				}
			} else {
				// handle kingdom
				if (neighborTile.getPlayer() == tile.getPlayer() && !(neighborTile.getKingdom() == tile.getKingdom())) {
					// combine kingdoms if owned by the same player
					combineKingdoms(gameState, neighborTile.getKingdom(), tile.getKingdom());
					gameState.setActiveKingdom(neighborTile.getKingdom());
					neighborTile.getKingdom().setWasActiveInCurrentTurn(true);
				} else if (neighborTile.getKingdom() == oldTileKingdom) {
					// remember neighbor tiles of the same kingdom as the old tile
					oldKingdomNeighborTiles.add(neighborTile);
				}
			}
		}
		// find out whether kingdom was potentially split
		boolean potentiallySplit = true;
		switch (oldKingdomNeighborTiles.size()) {
		case 2:
			// both tiles next to to each other --> no split possible
			if (gameState.getMap().getNeighborTiles(oldKingdomNeighborTiles.get(0))
					.contains(oldKingdomNeighborTiles.get(1))) {
				potentiallySplit = false;
			}
			break;
		case 3:
			// if the first or the second tile is next to both of the other ones, they are
			// all next to each other --> no split possible
			if (((gameState.getMap().getNeighborTiles(oldKingdomNeighborTiles.get(0))
					.contains(oldKingdomNeighborTiles.get(1)))
					&& (gameState.getMap().getNeighborTiles(oldKingdomNeighborTiles.get(0))
							.contains(oldKingdomNeighborTiles.get(2))))
					|| ((gameState.getMap().getNeighborTiles(oldKingdomNeighborTiles.get(1))
							.contains(oldKingdomNeighborTiles.get(0)))
							&& (gameState.getMap().getNeighborTiles(oldKingdomNeighborTiles.get(1))
									.contains(oldKingdomNeighborTiles.get(2))))) {
				potentiallySplit = false;
			}
			break;
		case 4:
			// if the other tiles are next to each other, the 4 oldKingdomNeighborTiles must
			// also be next to each other --> no split possible
			ArrayList<HexTile> notOldKingdomNeighborTiles = new ArrayList<HexTile>(
					gameState.getMap().getNeighborTiles(tile));
			notOldKingdomNeighborTiles.removeAll(oldKingdomNeighborTiles);
			if (notOldKingdomNeighborTiles.get(0) != null && notOldKingdomNeighborTiles.get(1) != null
					&& gameState.getMap().getNeighborTiles(notOldKingdomNeighborTiles.get(0))
							.contains(notOldKingdomNeighborTiles.get(1))) {
				potentiallySplit = false;
			}
			break;
		default:
			// 1 or 5 means no split possible
			potentiallySplit = false;
			break;
		}
		if (potentiallySplit || (oldTileKingdom != null && oldTileKingdom.getTiles().size() < 2)) {
			updateSplitKingdom(gameState, oldTileKingdom.getTiles());
		}
		placeObject(gameState, tile);
	}

	private static void placeObject(GameState gameState, HexTile tile) {
		tile.setContent(gameState.getHeldObject());
		gameState.setHeldObject(null);
	}

	private static void combineKingdoms(GameState gameState, Kingdom masterKingdom, Kingdom slaveKingdom) {
		// master kingdom will determine the new capital
		masterKingdom.getTiles().addAll(slaveKingdom.getTiles());
		masterKingdom.setSavings(masterKingdom.getSavings() + slaveKingdom.getSavings());
		if (!slaveKingdom.isDoneMoving()) {
			masterKingdom.setDoneMoving(false);
		}
		for (HexTile slaveKingdomTile : slaveKingdom.getTiles()) {
			slaveKingdomTile.setKingdom(masterKingdom);
			MapObject content = slaveKingdomTile.getContent();
			if (content != null && ClassReflection.isAssignableFrom(content.getClass(), Capital.class)) {
				// delete slave capital
				slaveKingdomTile.setContent(null);
			}
		}
		gameState.getKingdoms().remove(slaveKingdom);
	}

	private static void updateSplitKingdom(GameState gameState, LinkedHashSet<HexTile> tiles) {
		if (tiles.size() == 0) {
			return;
		}
		Kingdom oldKingdom = ((HexTile) tiles.toArray()[0]).getKingdom();
		// try to find a capital
		HexTile capitalTile = null;
		for (HexTile kingdomTile : tiles) {
			if (kingdomTile.getContent() != null
					&& ClassReflection.isAssignableFrom(kingdomTile.getContent().getClass(), Capital.class)) {
				capitalTile = kingdomTile;
				break;
			}
		}
		HexTile startTile;
		Kingdom newKingdom = null;
		if (capitalTile != null) {
			// capital exists --> keep it's kingdom
			startTile = capitalTile;
			newKingdom = startTile.getKingdom();
			newKingdom.setTiles(new LinkedHashSet<HexTile>());
		} else {
			// no capital exists --> create new kingdom
			// start from some other tile
			startTile = (HexTile) tiles.toArray()[0];
			newKingdom = new Kingdom(startTile.getPlayer());
			gameState.getKingdoms().add(newKingdom);
		}
		// expand outwards from startTile to find connected tiles
		LinkedList<HexTile> todoTiles = new LinkedList<HexTile>();
		HashSet<HexTile> doneTiles = new HashSet<HexTile>();
		todoTiles.add(startTile);
		while (todoTiles.size() > 0) {
			HexTile currentTile = todoTiles.removeFirst();
			newKingdom.getTiles().add(currentTile);
			currentTile.setKingdom(newKingdom);
			doneTiles.add(currentTile);
			for (HexTile expandTile : gameState.getMap().getNeighborTiles(currentTile)) {
				if (!doneTiles.contains(expandTile) && !todoTiles.contains(expandTile)
						&& (expandTile != null && expandTile.getKingdom() == oldKingdom)) {
					todoTiles.add(expandTile);
				}
			}
		}
		tiles.removeAll(newKingdom.getTiles());

		if (newKingdom.getTiles().size() < 2) {
			// delete capital, units and kingdom if too small
			for (HexTile tile : newKingdom.getTiles()) {
				if (tile.getContent() != null
						&& !ClassReflection.isAssignableFrom(tile.getContent().getClass(), Tree.class)) {
					tile.setContent(null);
				}
			}
			startTile.setKingdom(null);
			gameState.getKingdoms().remove(newKingdom);
		} else if (capitalTile == null) {
			// create capital if necessary
			createCapital(gameState, newKingdom);
		}
		// recursive call with the tiles that are not connected
		updateSplitKingdom(gameState, tiles);
		// remove old empty kingdom
		if (oldKingdom.getTiles().size() == 0) {
			gameState.getKingdoms().remove(oldKingdom);
		}
		return;
	}

	public static GameState endTurn(GameState gameState) {
		// check win condition; the winner can change if the human player recovers from
		// a really bad situation
		for (Kingdom kingdom : gameState.getKingdoms()) {
			if (kingdom.getPlayer() == gameState.getActivePlayer()
					&& kingdom.getTiles().size() >= gameState.getMap().getTiles().size() * WIN_LANDMASS_PERCENTAGE) {
				gameState.setWinner(kingdom.getPlayer());
			}
		}
		// update active player
		gameState.setPlayerTurn(gameState.getPlayerTurn() + 1);
		if (gameState.getPlayerTurn() >= gameState.getPlayers().size()) {
			gameState.setPlayerTurn(0);
			spreadTrees(gameState);
		}
		// check defeat condition
		playerLoop: for (Player player : gameState.getPlayers()) {
			if (player.isDefeated()) {
				continue;
			}
			for (Kingdom kingdom : gameState.getKingdoms()) {
				if (kingdom.getPlayer() == player) {
					continue playerLoop;
				}
			}
			// player has no kingdoms --> is defeated
			player.setDefeated(true);
		}
		// reset active kingdom
		gameState.setActiveKingdom(null);
		for (Kingdom kingdom : gameState.getKingdoms()) {
			// update savings
			if (kingdom.getPlayer() == gameState.getActivePlayer()) {
				kingdom.setSavings(kingdom.getSavings() + kingdom.getIncome());
				if (kingdom.getSavings() < kingdom.getSalaries()) {
					// destroy all units if they cannot get paid
					for (HexTile tile : kingdom.getTiles()) {
						if (tile.getContent() != null
								&& ClassReflection.isAssignableFrom(tile.getContent().getClass(), Unit.class)) {
							tile.setContent(null);
						}
					}
				} else {
					kingdom.setSavings(kingdom.getSavings() - kingdom.getSalaries());
					// reset canAct and hasActed state
					for (HexTile tile : kingdom.getTiles()) {
						if (tile.getContent() != null
								&& ClassReflection.isAssignableFrom(tile.getContent().getClass(), Unit.class)) {
							((Unit) tile.getContent()).setCanAct(true);
						}
					}
				}
			}
			// reset wasActiveInCurrentTurn
			kingdom.setWasActiveInCurrentTurn(false);
		}
		return gameState;
	}

	private static void spreadTrees(GameState gameState) {
		HashSet<HexTile> newTreeTiles = new HashSet<HexTile>();
		for (HexTile tile : gameState.getMap().getTiles().values()) {
			if (tile.getContent() != null
					&& ClassReflection.isAssignableFrom(tile.getContent().getClass(), Tree.class)) {
				if (gameState.getRandom().nextFloat() <= TREE_SPREAD_RATE) {
					ArrayList<HexTile> candidates = new ArrayList<HexTile>();
					for (HexTile neighbor : gameState.getMap().getNeighborTiles(tile)) {
						if (neighbor != null && neighbor.getContent() == null) {
							candidates.add(neighbor);
						}
					}
					if (candidates.size() > 0) {
						newTreeTiles.add(candidates.get(gameState.getRandom().nextInt(candidates.size())));
						candidates.clear();
					}
				}
			} else if (tile.getContent() == null) {
				if (gameState.getRandom().nextFloat() <= TREE_SPAWN_RATE) {
					newTreeTiles.add(tile);
				}
			}
		}
		for (HexTile tile : newTreeTiles) {
			tile.setContent(new Tree());
		}
	}

	public static void buyPeasant(GameState gameState) {
		gameState.getActiveKingdom().setSavings(gameState.getActiveKingdom().getSavings() - Unit.COST);
		gameState.setHeldObject(new Unit(UnitTypes.PEASANT));
	}

	public static void buyCastle(GameState gameState) {
		gameState.getActiveKingdom().setSavings(gameState.getActiveKingdom().getSavings() - Castle.COST);
		gameState.setHeldObject(new Castle());
	}

	public static void placeTile(GameState gameState, Vector2 hexCoords, Player player) {
		HexTile newTile = new HexTile(player, hexCoords);
		gameState.getMap().getTiles().put(hexCoords, newTile);
	}

	public static int getProtectionLevel(GameState gameState, HexTile tile) {
		int protectionLevel = 0;
		if (tile.getContent() != null) {
			protectionLevel = tile.getContent().getStrength();
		}
		for (HexTile neighbor : gameState.getMap().getNeighborTiles(tile)) {
			if (neighbor != null && neighbor.getKingdom() != null && tile.getKingdom() == neighbor.getKingdom()
					&& neighbor.getContent() != null && neighbor.getContent().getStrength() > protectionLevel) {
				protectionLevel = neighbor.getContent().getStrength();
			}
		}
		return protectionLevel;
	}

	public static boolean hasActivePlayerlikelyForgottenAKingom(GameState gameState) {
		boolean result = false;
		kingdomLoop: for (Kingdom kingdom : gameState.getKingdoms()) {
			if (kingdom.getPlayer() == gameState.getActivePlayer() && !kingdom.isWasActiveInCurrentTurn()) {
				// can buy castle or any unit that is more expensive
				if (InputValidationHelper.checkBuyObject(gameState, Castle.COST)) {
					result = true;
					break kingdomLoop;
				}
				// has unit stronger than peasant
				boolean hasPeasant = false;
				boolean hasTree = false;
				for (HexTile tile : kingdom.getTiles()) {
					if (tile.getContent() != null
							&& ClassReflection.isAssignableFrom(tile.getContent().getClass(), Unit.class)) {
						if (tile.getContent().getStrength() > 1) {
							result = true;
							break kingdomLoop;
						} else if (((Unit) tile.getContent()).getUnitType() == UnitTypes.PEASANT) {
							hasPeasant = true;
						}
					} else if (tile.getContent() != null
							&& ClassReflection.isAssignableFrom(tile.getContent().getClass(), Tree.class)) {
						hasTree = true;
					}
				}
				boolean canBuyPeasant = kingdom.getSavings() >= Unit.COST;
				// has or can get peasant that can conquer something or destroy tree
				if (hasPeasant || canBuyPeasant) {
					if (hasTree) {
						result = true;
						break kingdomLoop;
					}
					// there is a neighbor tile which can be conquered by the peasant
					for (HexTile tile : kingdom.getTiles()) {
						for (HexTile neighborTile : gameState.getMap().getNeighborTiles(tile)) {
							if (neighborTile != null && neighborTile.getKingdom() != tile.getKingdom()
									&& getProtectionLevel(gameState, neighborTile) == 0) {
								result = true;
								break kingdomLoop;
							}
						}
					}
				}
			}
		}
		return result;
	}
}
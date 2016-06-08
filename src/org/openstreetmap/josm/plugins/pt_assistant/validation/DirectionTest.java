package org.openstreetmap.josm.plugins.pt_assistant.validation;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.command.ChangeCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.OsmUtils;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.validation.Severity;
import org.openstreetmap.josm.data.validation.Test;
import org.openstreetmap.josm.data.validation.TestError;
import org.openstreetmap.josm.gui.dialogs.relation.sort.WayConnectionType;
import org.openstreetmap.josm.gui.dialogs.relation.sort.WayConnectionTypeCalculator;
import org.openstreetmap.josm.plugins.pt_assistant.utils.RouteUtils;

public class DirectionTest extends Test {

	public static final int ERROR_CODE_DIRECTION = 3731;
	public static final int ERROR_CODE_ROUNDABOUT = 3732;

	public DirectionTest() {
		super(tr("Direction Test"), tr("Checks if the route runs against the direction of underlying one-way roads"));
	}

	@Override
	public void visit(Relation r) {

		if (RouteUtils.isTwoDirectionRoute(r)) {

			List<RelationMember> waysToCheck = new ArrayList<>();

			for (RelationMember rm : r.getMembers()) {
				if (RouteUtils.isPTWay(rm) && rm.getType().equals(OsmPrimitiveType.WAY)) {
					waysToCheck.add(rm);
				}
			}

			if (waysToCheck.isEmpty()) {
				return;
			}

			WayConnectionTypeCalculator connectionTypeCalculator = new WayConnectionTypeCalculator();
			final List<WayConnectionType> links = connectionTypeCalculator.updateLinks(waysToCheck);

			for (int i = 0; i < links.size(); i++) {
				if ((OsmUtils.isTrue(waysToCheck.get(i).getWay().get("oneway"))
						&& links.get(i).direction.equals(WayConnectionType.Direction.BACKWARD))
						|| (OsmUtils.isReversed(waysToCheck.get(i).getWay().get("oneway"))
								&& links.get(i).direction.equals(WayConnectionType.Direction.FORWARD))) {

					// At this point, the PTWay is going against the oneway
					// direction. Check if this road allows buses to disregard
					// the oneway restriction:

					if (!waysToCheck.get(i).getWay().hasTag("busway", "lane")
							&& !waysToCheck.get(i).getWay().hasTag("oneway:bus", "no")
							&& !waysToCheck.get(i).getWay().hasTag("busway", "opposite_lane")) {
						List<OsmPrimitive> primitiveList = new ArrayList<>(2);
						primitiveList.add(0, r);
						primitiveList.add(1, waysToCheck.get(i).getWay());
						errors.add(new TestError(this, Severity.WARNING,
								tr("PT: Route passes a oneway road in wrong direction"), ERROR_CODE_DIRECTION,
								primitiveList));
					}

				}

				if (links.get(i).direction.equals(WayConnectionType.Direction.ROUNDABOUT_LEFT)
						|| links.get(i).direction.equals(WayConnectionType.Direction.ROUNDABOUT_RIGHT)) {
					List<OsmPrimitive> primitiveList = new ArrayList<>(2);
					primitiveList.add(0, r);
					primitiveList.add(1, waysToCheck.get(i).getWay());
					errors.add(new TestError(this, Severity.WARNING,
							tr("PT: Route passes on an unsplit roundabout"), ERROR_CODE_ROUNDABOUT,
							primitiveList));
				}
			}

		}
	}

	@Override
	public Command fixError(TestError testError) {

		List<Command> commands = new ArrayList<>(50);

		if (testError.getTester().getClass().equals(DirectionTest.class) && testError.isFixable()) {
			List<OsmPrimitive> primitiveList = (List<OsmPrimitive>) testError.getPrimitives();
			Relation originalRelation = (Relation) primitiveList.get(0);
			Way wayToRemove = (Way) primitiveList.get(1);
			
			Relation modifiedRelation = new Relation(originalRelation);
			List<RelationMember> modifiedRelationMembers = new ArrayList<>(originalRelation.getMembersCount()-1);
			
			// copy PT stops first, PT ways last:
			for (RelationMember rm: originalRelation.getMembers()) {
				if (RouteUtils.isPTStop(rm)) {
					
					if (rm.getRole().equals("stop_position")) {
						if (rm.getType().equals(OsmPrimitiveType.NODE)) {
							RelationMember newMember = new RelationMember("stop", rm.getNode());
							modifiedRelationMembers.add(newMember);
						} else { // if it is a way:
							RelationMember newMember = new RelationMember("stop", rm.getWay());
							modifiedRelationMembers.add(newMember);
						}
					} else { 
						// if the relation member does not have the role "stop_position":
						modifiedRelationMembers.add(rm);
					}
					
				} 
			}
			
			// now copy PT ways:
			for (RelationMember rm: originalRelation.getMembers()) {
				if (RouteUtils.isPTWay(rm)) {
					Way wayToCheck = rm.getWay();
					if (wayToCheck != wayToRemove) {
						if (rm.getRole().equals("forward") || rm.getRole().equals("backward")) {
							RelationMember modifiedMember = new RelationMember("", wayToCheck);
							modifiedRelationMembers.add(modifiedMember);
						} else {
							modifiedRelationMembers.add(rm);
						}
					}
				}
			}
			
			modifiedRelation.setMembers(modifiedRelationMembers);
			
			ChangeCommand changeCommand = new ChangeCommand(originalRelation, modifiedRelation);
			commands.add(changeCommand);
			
		}
		
		if (commands.isEmpty()) {
			return null;
		}

		if (commands.size() == 1) {
			return commands.get(0);
		}
		

		return new SequenceCommand(tr("Remove way from route if it does not match the route type"), commands);	
		
	}

	/**
	 * Checks if the test error is fixable
	 */
	@Override
	public boolean isFixable(TestError testError) {
		if (testError.getCode() == ERROR_CODE_DIRECTION) {
			return true;
		}
		return false;
	}

}
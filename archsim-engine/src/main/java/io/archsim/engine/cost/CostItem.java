package io.archsim.engine.cost;

/**
 * One priced quantity of a cost line, such as "3.94 M requests × $0.20/M".
 *
 * @param what what is counted, such as {@code requests}
 * @param quantity how many a month
 * @param unit the unit of one, such as {@code request}
 * @param per how many units the unit price is for
 * @param unitPrice the price of {@code per} units
 * @param monthly the quantity's monthly cost
 * @param text the quantity and its price, as the editor shows them
 */
public record CostItem(String what, double quantity, String unit, double per, double unitPrice, double monthly,
		String text) {

	/** A unit price per million reads as {@code $0.20/M}. */
	static final double MILLION = 1e6;

	/**
	 * A priced quantity whose unit price is for one unit.
	 * @param what what is counted
	 * @param quantity how many a month
	 * @param unitPrice the price of one unit
	 * @param unit the unit of one
	 * @return the item
	 */
	static CostItem of(String what, double quantity, double unitPrice, String unit) {
		return new CostItem(what, quantity, unit, 1.0, unitPrice, quantity * unitPrice,
				text(what, quantity, unitPrice, unit));
	}

	/**
	 * A priced quantity whose unit price is for a million units.
	 * @param what what is counted
	 * @param quantity how many a month
	 * @param unitPrice the price of a million units
	 * @param unit the unit of one
	 * @return the item
	 */
	static CostItem perMillion(String what, double quantity, double unitPrice, String unit) {
		return new CostItem(what, quantity, unit, MILLION, unitPrice, quantity / MILLION * unitPrice,
				text(what, quantity, unitPrice, "M"));
	}

	private static String text(String what, double quantity, double unitPrice, String per) {
		return CostText.quantity(quantity) + " " + what + " × $" + CostText.price(unitPrice) + "/" + per;
	}

}

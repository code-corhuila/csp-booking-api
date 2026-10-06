package co.edu.corhuila.csp.booking.application.port.out;

/**
 * The hold was no longer an overdue HELD one when the sweep tried to expire it: a confirmation
 * won the race. It is an expected outcome of the sweep, not a failure of the run.
 */
public class HoldNoLongerOverdueException extends IllegalStateException {

    public HoldNoLongerOverdueException(String message) {
        super(message);
    }
}

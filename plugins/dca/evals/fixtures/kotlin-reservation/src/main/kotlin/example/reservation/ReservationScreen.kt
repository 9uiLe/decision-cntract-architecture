package example.reservation

class ReservationScreen(private val service: ReservationService, private val display: (String) -> Unit) {
    fun submit(request: ReservationRequest) {
        if (service.reserve(request)) {
            display("予約しました")
        } else {
            display("予約できませんでした")
        }
    }
}

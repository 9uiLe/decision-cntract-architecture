package example.profile

class UpdateNickname(private val save: (String) -> Unit) {
    fun update(value: String): String? {
        if (value.isEmpty() || value.length > 20) {
            return "ニックネームは 1 文字以上 20 文字以下で入力してください"
        }
        save(value)
        return null
    }
}

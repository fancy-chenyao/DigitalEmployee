package Agent

import android.util.Log
import org.json.JSONObject

class GPTMessage(responseString: String) {
    private var action: JSONObject
    private var args: JSONObject

    init {
        try {
            Log.d("TAG", responseString)
            action = JSONObject(responseString)
            args = action.getJSONObject("parameters")
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    fun getActionName(): String {
        try {
            return action.getString("name")
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    fun getArgs(): JSONObject {
        return args
    }
}
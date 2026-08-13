/**
 * HTTP client with ApiResponse auto-unwrap interceptor.
 * Avoids repetitive `const result = data.data || data` boilerplate.
 */
import axios from 'axios'

const http = axios.create()

http.interceptors.response.use(
  response => {
    if (response.data && typeof response.data === 'object' && 'data' in response.data) {
      response.data = response.data.data
    }
    return response
  },
  error => Promise.reject(error)
)

export default http

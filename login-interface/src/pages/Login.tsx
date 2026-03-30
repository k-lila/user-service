import LoginBox from '../components/LoginBox'
import { Navbar } from '../components/NavBar'

export const Login = () => {
  const logged = localStorage.getItem('token')
  return (
    <>
      {logged ? <Navbar /> : null}
      <div className="min-h-screen flex items-center justify-center bg-gray-100">
        <LoginBox />
      </div>
    </>
  )
}

import { Navbar } from '../components/NavBar'
import { RegisterBox } from '../components/RegisterBox'

export const Register = () => {
  const loggedIn = localStorage.getItem('token')
  return (
    <>
      {loggedIn ? <Navbar /> : null}
      <div className="min-h-screen flex items-center justify-center bg-gray-100">
        <RegisterBox />
      </div>
    </>
  )
}
